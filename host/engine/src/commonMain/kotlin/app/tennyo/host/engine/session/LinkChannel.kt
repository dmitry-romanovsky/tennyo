package app.tennyo.host.engine.session

import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkFrameAssembler
import app.tennyo.contract.OversizedLinkFrameException
import app.tennyo.contract.encodeLinkFrame
import app.tennyo.contract.linkFrameMessages
import app.tennyo.contract.wireName
import app.tennyo.host.engine.auth.CommandStream
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.transport.LinkSocket
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/**
 * One WebSocket on [app.tennyo.contract.LinkEndpoints.CHANNEL], from its [LinkFrame.Open] to its close. The listener
 * hands it every text message in arrival order and reports when the socket closes; the channel reassembles parts,
 * finds the session, and hands verified frames to that session's [SessionHost].
 *
 * Any frame the channel cannot accept ends it: a [LinkFrame.Fault] explains why, then the socket closes. Until it is
 * open nothing on it is authenticated, so it buffers no parts and the listener calls [expireUnopened] once
 * [OPEN_DEADLINE_MILLIS] after the upgrade, before a silent socket can hold one of the few link-socket slots.
 */
class LinkChannel(
    private val socket: LinkSocket,
    private val sessions: (sessionId: String, token: String) -> SessionMatch? = HostRegistry::find,
) {
    private val assembler = LinkFrameAssembler()
    private val sendLock = HostMonitor()
    private val partIds = AtomicLong(0L)
    private val binding = AtomicReference<ChannelBinding?>(null)
    private val ended = AtomicBoolean(false)

    val isOpen: Boolean get() = binding.load() != null && !ended.load()

    private fun nextPartId(): Long = partIds.addAndFetch(1L)

    /** One text message from the socket. */
    fun receive(message: String) {
        if (ended.load()) return
        val frame = try {
            assembler.accept(message)
        } catch (oversized: OversizedLinkFrameException) {
            return fail(LinkFaultCode.FRAME_TOO_LARGE, oversized.message)
        } catch (malformed: IllegalArgumentException) {
            LOG.debug { "Refused an unreadable link frame: ${malformed.message}" }
            return fail(LinkFaultCode.INVALID_CALL, "The frame could not be read.")
        }
        val bound = binding.load()
        when {
            frame == null && bound == null -> fail(LinkFaultCode.INVALID_CALL, "A channel opens with one whole frame.")
            frame == null -> Unit
            bound == null && frame is LinkFrame.Open -> open(frame)
            bound != null && frame is LinkFrame.Call -> bound.host.handleCall(this, bound, frame, message.length)
            else -> fail(LinkFaultCode.INVALID_CALL, "Unexpected ${frame::class.simpleName} frame.")
        }
    }

    /** Closes the channel if no [LinkFrame.Open] has bound it yet. */
    fun expireUnopened() {
        if (binding.load() == null) fail(LinkFaultCode.INVALID_CALL, "The channel was not opened in time.")
    }

    /** The socket closed, from either side. */
    fun closed() {
        ended.store(true)
        binding.exchange(null)?.let { it.host.detachChannel(this) }
    }

    fun send(frame: LinkFrame): Boolean = sendMessages(encodeLinkFrame(frame, ::nextPartId))

    /** Sends a frame already encoded, as a replayed push is. */
    fun sendEncoded(frameText: String): Boolean = sendMessages(linkFrameMessages(frameText, ::nextPartId))

    /** Refuses what the client sent: a [LinkFrame.Fault], then the socket closes. */
    fun fail(fault: LinkFaultCode, message: String? = null, callId: Long? = null) {
        if (ended.exchange(true)) return
        LOG.debug { "Closing a link channel: ${fault.wireName}" }
        sendMessages(encodeLinkFrame(LinkFrame.Fault(callId, fault, message), ::nextPartId))
        socket.close(POLICY_VIOLATION, fault.wireName)
    }

    fun close(code: Int, reason: String) {
        ended.store(true)
        socket.close(code, reason)
    }

    internal fun bind(next: ChannelBinding) {
        binding.store(next)
    }

    private fun open(frame: LinkFrame.Open) {
        val match = sessions(frame.sessionId, frame.sessionToken)
            ?.takeIf { it.credential is SessionCredential.SessionToken }
            ?: return fail(LinkFaultCode.SESSION_NOT_FOUND, "The session is no longer available.")
        match.host.openChannel(this, match.session, frame)?.let { fault -> fail(fault) }
    }

    private fun sendMessages(messages: List<String>): Boolean = sendLock.guarded {
        messages.all { text ->
            socket.sendText(text) { cause ->
                LOG.warn("Unable to send on a link channel", cause)
                if (!ended.exchange(true)) {
                    socket.close(INTERNAL_ERROR, "Send failed.")
                    closed()
                }
            }
        }
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
        const val POLICY_VIOLATION = 1008
        const val INTERNAL_ERROR = 1011

        /** How long an upgraded socket may wait before its first frame opens it; the app sends it at once. */
        const val OPEN_DEADLINE_MILLIS = 10_000L
        private val LOG = HostLogger("LinkChannel")
    }
}

/** What an opened channel is bound to: its session, the stream its calls are verified on, and its ordered calls. */
class ChannelBinding(
    val host: SessionHost,
    val session: HostSession,
    val stream: CommandStream,
    val orderedCalls: OrderedCallQueue,
)
