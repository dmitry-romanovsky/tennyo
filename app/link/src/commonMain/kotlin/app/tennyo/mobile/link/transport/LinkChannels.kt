package app.tennyo.mobile.link.transport

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkFrameAssembler
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkResult
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.encodeLinkFrame
import app.tennyo.crypto.LinkProofs
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.decodeBase64UrlOrNull
import app.tennyo.crypto.sha256Url
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.proof
import app.tennyo.mobile.link.toLinkFault
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Opens the authenticated channel to a host: the WebSocket, the [LinkFrame.Open] with this device's proof, and then an
 * [OpenLinkChannel] that carries MAC'd calls and hands every push to its owner.
 */
class LinkChannels(
    private val httpClient: HttpClient,
    private val deviceIdentity: DeviceIdentity,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Keeps a channel to [ticket] open until it closes, resuming after [lastPushSeq]. [onOpened] runs once the host
     * accepted the open, on the reading coroutine, so it must not suspend on calls; [onPush] likewise only queues.
     * Returns when the host closed the channel, and throws the [LinkFault] that ended it otherwise.
     */
    suspend fun run(
        ticket: LinkTicket,
        lastPushSeq: Long?,
        onOpened: (OpenLinkChannel) -> Unit,
        onPush: (LinkFrame.Push) -> Unit,
    ) {
        requirePinnedTransport(ticket)
        val proof = deviceIdentity.proof(ticket, LinkEndpoints.CHANNEL, lastPushSeq?.toString().orEmpty(), now())
        val open = LinkFrame.Open(ticket.sessionId, ticket.token, proof, lastPushSeq)
        var channel: OpenLinkChannel? = null
        try {
            httpClient.webSocket(request = { url(ticket.linkUrl(LinkEndpoints.CHANNEL, scheme = "wss")) }) {
                var partIds = 0L
                encodeLinkFrame(open) { ++partIds }.forEach { send(Frame.Text(it)) }
                val assembler = LinkFrameAssembler()
                for (message in incoming) {
                    val frame = (message as? Frame.Text)?.readText()?.let(assembler::accept) ?: continue
                    channel = read(frame, channel, ticket, onOpened, onPush)
                }
            }
            // toLinkFault keeps the original as the fault's cause, so nothing is lost on the way out.
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") throwable: Throwable) {
            // Whatever the socket did, the calls waiting on it fail with one fault, and so does the caller.
            val fault = throwable.toLinkFault()
            channel?.closeAll(fault)
            throw fault
        }
        channel?.closeAll(LinkFault.StreamClosed(beforeLiveSync = false))
    }

    /** Applies one frame from the host and returns the channel it leaves open, throwing what ends the channel. */
    private suspend fun DefaultClientWebSocketSession.read(
        frame: LinkFrame,
        channel: OpenLinkChannel?,
        ticket: LinkTicket,
        onOpened: (OpenLinkChannel) -> Unit,
        onPush: (LinkFrame.Push) -> Unit,
    ): OpenLinkChannel? {
        when (frame) {
            is LinkFrame.Opened -> {
                if (frame.securityVersion != ProtocolVersions.SECURITY_VERSION) {
                    throw LinkFault.ProtocolMismatch(frame.securityVersion)
                }
                val signer = CallSigner(ticket, frame, deviceIdentity.fingerprint)
                return OpenLinkChannel(this, signer, frame).also(onOpened)
            }

            is LinkFrame.Reply -> channel?.complete(frame.id, frame.result)
            is LinkFrame.Fault -> {
                val fault = LinkFault.fromLink(frame.fault, frame.message.orEmpty())
                // A fault with no call id is about the channel itself, which the host closes after sending it.
                val id = frame.id ?: throw fault
                channel?.fail(id, fault)
            }

            is LinkFrame.Push -> onPush(frame)
            is LinkFrame.Open, is LinkFrame.Call, is LinkFrame.Part -> Unit
        }
        return channel
    }

    /**
     * Opens a channel just long enough to run [call], as the background keep-alive and the end notice do. The channel
     * closes as soon as the host answers.
     */
    suspend fun call(ticket: LinkTicket, call: LinkCall): LinkResult = coroutineScope {
        val answer = CompletableDeferred<LinkResult>()
        val connection = launch {
            runCatching {
                run(
                    ticket = ticket,
                    lastPushSeq = null,
                    onOpened = { channel ->
                        launch {
                            runCatching { channel.call(call) }
                                .fold(answer::complete, answer::completeExceptionally)
                            channel.close()
                        }
                    },
                    onPush = { },
                )
            }.onFailure { failure -> answer.completeExceptionally(failure) }
            answer.completeExceptionally(LinkFault.StreamClosed(beforeLiveSync = true))
        }
        try {
            answer.await()
        } finally {
            connection.cancel()
        }
    }
}

/** An open channel: calls go out MAC'd in sequence, and each waits for the reply with its id. */
class OpenLinkChannel internal constructor(
    private val session: DefaultClientWebSocketSession,
    private val signer: CallSigner,
    val opened: LinkFrame.Opened,
) : LinkCalls {
    private val sendLock = Mutex()
    private val pendingLock = Mutex()
    private val pending = mutableMapOf<Long, CompletableDeferred<LinkResult>>()
    private var nextId = 0L
    private var nextPartId = 0L
    private var closedBy: LinkFault? = null

    /** Runs [call] on the host; every failure is a [LinkFault]. */
    override suspend fun call(call: LinkCall): LinkResult {
        val answer = CompletableDeferred<LinkResult>()
        val id = sendLock.withLock {
            val callId = ++nextId
            pendingLock.withLock {
                closedBy?.let { throw it }
                pending[callId] = answer
            }
            val messages = encodeLinkFrame(signer.sign(callId, call)) { ++nextPartId }
            runCatching { messages.forEach { session.send(Frame.Text(it)) } }
                .onFailure { throwable ->
                    pendingLock.withLock { pending.remove(callId) }
                    throw throwable.toLinkFault()
                }
            callId
        }
        return try {
            answer.await()
        } finally {
            // A caller that gives up, by timeout or cancellation, takes its entry along; a late reply finds nothing.
            if (!answer.isCompleted) withContext(NonCancellable) { pendingLock.withLock { pending.remove(id) } }
        }
    }

    internal suspend fun complete(id: Long, result: LinkResult) {
        pendingLock.withLock { pending.remove(id) }?.complete(result)
    }

    internal suspend fun fail(id: Long, fault: LinkFault) {
        pendingLock.withLock { pending.remove(id) }?.completeExceptionally(fault)
    }

    /** Closes the socket; waiting calls fail as the channel goes. */
    suspend fun close() {
        session.close()
    }

    /** Fails every waiting call and every later one with [fault]; the channel is gone. */
    internal suspend fun closeAll(fault: LinkFault) {
        val waiting = pendingLock.withLock {
            closedBy = fault
            pending.values.toList().also { pending.clear() }
        }
        waiting.forEach { it.completeExceptionally(fault) }
    }
}

/** MACs the calls of one stream with the key [LinkFrame.Opened] seeds; the sequence only moves forward. */
internal class CallSigner(
    private val ticket: LinkTicket,
    private val opened: LinkFrame.Opened,
    private val approvedDeviceFingerprint: String,
) {
    private val commandKey: ByteArray = SecurityDerivations.commandMacKey(
        commandKeySeed = requireNotNull(opened.commandKeySeed.decodeBase64UrlOrNull()) {
            "The host did not send a valid command key seed."
        },
        sessionToken = ticket.token,
        streamId = opened.streamId,
        approvedDeviceFingerprint = approvedDeviceFingerprint,
    )
    private val sessionTokenHash = sha256Url(ticket.token)
    private var lastSeq = 0L

    fun sign(id: Long, call: LinkCall): LinkFrame.Call {
        val seq = ++lastSeq
        return LinkFrame.Call(id = id, seq = seq, mac = mac(seq, id, call), call = call)
    }

    fun mac(seq: Long, id: Long, call: LinkCall): String =
        LinkProofs.call(
            commandKey = commandKey,
            transcript = SecurityTranscripts.callMac(
                sessionId = ticket.sessionId,
                sessionTokenHash = sessionTokenHash,
                companionFingerprint = ticket.fingerprint,
                approvedDeviceFingerprint = approvedDeviceFingerprint,
                streamId = opened.streamId,
                sequence = seq,
                callId = id,
                callJsonHash = sha256Url(LinkJson.encodeToString(LinkCall.serializer(), call)),
            ),
        )
}
