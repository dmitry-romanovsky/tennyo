package app.tennyo.host.engine.session

import app.tennyo.contract.DeviceProof
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkFrameAssembler
import app.tennyo.contract.encodeLinkFrame
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.decodeBase64Url
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.auth.callMac
import app.tennyo.host.engine.auth.deviceKeyHash
import app.tennyo.host.engine.auth.deviceProofMessage
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.HostTestSigner
import app.tennyo.host.engine.platform.HostTestSigningKey
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.transport.LinkSocket

/** A phone's device key, signing proofs the way the app does. Every proof carries a fresh nonce. */
class TestDevice {
    private val key: HostTestSigningKey = HostTestSigner.newKey()
    private var nonces = 0
    val publicKey: String = key.publicKeySpki.toBase64Url()
    val fingerprint: String = deviceKeyHash(publicKey)

    fun proof(session: HostSession, endpoint: String, requestText: String = "", signedAt: Long): DeviceProof =
        proof(session.artifact, endpoint, requestText, signedAt)

    fun proof(
        ticket: SessionTicket,
        endpoint: String,
        requestText: String,
        signedAt: Long,
        /** The token that authenticated the request — the pairing token when the device is pairing by name. */
        authenticatedToken: String = ticket.token,
    ): DeviceProof {
        val unsigned = DeviceProof(
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            nonce = "nonce-${++nonces}",
            signedAtEpochMillis = signedAt,
            signature = "",
        )
        val message = deviceProofMessage(ticket, endpoint, requestText, unsigned, sha256Url(authenticatedToken))
        return unsigned.copy(signature = HostTestSigner.sign(key, message).toBase64Url())
    }
}

/** The listener's side of a socket: records what the host sends and how it closed. */
class FakeLinkSocket : LinkSocket {
    private val monitor = HostMonitor()
    private val messages = mutableListOf<String>()
    var failSends = false
    var closedWith: Pair<Int, String>? = null

    val sent: List<String> get() = monitor.guarded { messages.toList() }

    /** Every frame sent so far, with parts reassembled. */
    val frames: List<LinkFrame>
        get() {
            val assembler = LinkFrameAssembler()
            return sent.mapNotNull(assembler::accept)
        }

    override fun sendText(text: String, onSendFailed: (Throwable?) -> Unit): Boolean {
        if (failSends) {
            onSendFailed(null)
            return false
        }
        monitor.guarded { messages += text }
        return true
    }

    override fun close(code: Int, reason: String) {
        closedWith = code to reason
    }
}

/**
 * One phone connected over a [LinkChannel] to [host]: it opens with a proof from [device] and MACs its calls with the
 * stream key from [LinkFrame.Opened], exactly as the app does.
 */
class TestChannel(
    private val host: SessionHost,
    private val ticket: SessionTicket,
    private val device: TestDevice,
    private val now: () -> Long,
) {
    val socket = FakeLinkSocket()
    val channel = LinkChannel(socket) { sessionId, token -> host.match(sessionId, token) }
    private var nextSeq = 0L
    private var nextId = 0L
    private var nextPartId = 0L

    val opened: LinkFrame.Opened? get() = socket.frames.filterIsInstance<LinkFrame.Opened>().firstOrNull()

    fun open(lastPushSeq: Long? = null, proof: DeviceProof = openProof(lastPushSeq)): TestChannel = apply {
        receive(LinkFrame.Open(ticket.sessionId, ticket.token, proof, lastPushSeq))
    }

    fun openProof(lastPushSeq: Long?): DeviceProof =
        device.proof(ticket, LinkEndpoints.CHANNEL, lastPushSeq?.toString().orEmpty(), now())

    /** Sends [call] as the next frame of this stream and returns the frame, so a test can replay or alter it. */
    fun call(call: LinkCall): LinkFrame.Call = signed(call).also(::receive)

    fun signed(call: LinkCall, seq: Long = ++nextSeq, id: Long = ++nextId): LinkFrame.Call {
        val opened = checkNotNull(opened) { "The channel is not open." }
        val commandKey = SecurityDerivations.commandMacKey(
            commandKeySeed = opened.commandKeySeed.decodeBase64Url(),
            sessionToken = ticket.token,
            streamId = opened.streamId,
            approvedDeviceFingerprint = device.fingerprint,
        )
        val mac = callMac(commandKey, ticket, device.fingerprint, opened.streamId, seq, id, call)
        return LinkFrame.Call(id = id, seq = seq, mac = mac, call = call)
    }

    /** Sends [frame] the way the app does: one message, or ordered parts when it is too long for one. */
    fun receive(frame: LinkFrame) {
        encodeLinkFrame(frame) { ++nextPartId }.forEach(channel::receive)
    }

    fun replyTo(call: LinkFrame.Call): LinkFrame? =
        socket.frames.firstOrNull { (it as? LinkFrame.Reply)?.id == call.id || (it as? LinkFrame.Fault)?.id == call.id }

    fun pushes(): List<LinkFrame.Push> = socket.frames.filterIsInstance<LinkFrame.Push>()
}
