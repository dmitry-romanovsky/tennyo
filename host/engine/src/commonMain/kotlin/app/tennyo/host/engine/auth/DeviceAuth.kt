package app.tennyo.host.engine.auth

import app.tennyo.contract.DeviceProof
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.ProtocolVersions
import app.tennyo.crypto.LinkProofs
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.constantTimeEquals
import app.tennyo.crypto.decodeBase64Url
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import app.tennyo.crypto.verifyEcdsaP256Sha256
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.SessionTicket

private const val MAX_ACCEPTED_PROOF_NONCES = 4_096

/**
 * Verifies the calls of one channel: each [LinkFrame.Call] must carry the next larger sequence number and a MAC under
 * the key derived for this stream and the approved device. A call that fails either check is never run.
 */
class CommandStream(
    private val ticket: SessionTicket,
    val streamId: String,
    private val approvedDeviceFingerprint: String,
    commandKeySeed: ByteArray,
) {
    val commandKeySeedBase64: String = commandKeySeed.toBase64Url()
    private val commandKey = SecurityDerivations.commandMacKey(
        commandKeySeed = commandKeySeed,
        sessionToken = ticket.token,
        streamId = streamId,
        approvedDeviceFingerprint = approvedDeviceFingerprint,
    )
    private var lastSequence = 0L
    private val monitor = HostMonitor()

    /** `null` when [frame] is the next authentic call on this stream, otherwise why it is refused. */
    fun verify(frame: LinkFrame.Call): LinkFaultCode? = monitor.guarded {
        if (frame.seq <= lastSequence) return@guarded LinkFaultCode.REPLAYED
        val expected = callMac(commandKey, ticket, approvedDeviceFingerprint, streamId, frame.seq, frame.id, frame.call)
        if (!constantTimeEquals(expected, frame.mac)) return@guarded LinkFaultCode.MAC_INVALID
        lastSequence = frame.seq
        null
    }
}

/** The MAC a [LinkFrame.Call] carries; the client computes the same over the same call. */
fun callMac(
    commandKey: ByteArray,
    ticket: SessionTicket,
    approvedDeviceFingerprint: String,
    streamId: String,
    sequence: Long,
    callId: Long,
    call: LinkCall,
): String =
    LinkProofs.call(
        commandKey = commandKey,
        transcript = SecurityTranscripts.callMac(
            sessionId = ticket.sessionId,
            sessionTokenHash = sha256Url(ticket.token),
            companionFingerprint = ticket.fingerprint,
            approvedDeviceFingerprint = approvedDeviceFingerprint,
            streamId = streamId,
            sequence = sequence,
            callId = callId,
            callJsonHash = sha256Url(LinkJson.encodeToString(LinkCall.serializer(), call)),
        ),
    )

/**
 * Proof nonces seen within [LinkLimits.PROOF_FRESHNESS_MILLIS]. A proof is only accepted while fresh, so remembering
 * its nonce for that long is enough to refuse every replay of it.
 */
class NonceStore(
    private val ttlMillis: Long = LinkLimits.PROOF_FRESHNESS_MILLIS * 2,
    private val maxEntries: Int = MAX_ACCEPTED_PROOF_NONCES,
) {
    private val monitor = HostMonitor()
    private val acceptedAtByNonce = mutableMapOf<String, Long>()

    /** Records [nonceKey]; `false` when it was already recorded. */
    fun accept(nonceKey: String, nowEpochMillis: Long = hostWallClockMillis()): Boolean = monitor.guarded {
        acceptedAtByNonce.entries.removeAll { (_, acceptedAt) -> nowEpochMillis - acceptedAt > ttlMillis }
        if (acceptedAtByNonce.containsKey(nonceKey)) return@guarded false
        acceptedAtByNonce[nonceKey] = nowEpochMillis
        val extra = acceptedAtByNonce.size - maxEntries
        if (extra > 0) {
            val oldest = acceptedAtByNonce.entries.sortedBy { it.value }.take(extra).map { it.key }
            oldest.forEach(acceptedAtByNonce::remove)
        }
        true
    }

    fun size(): Int = monitor.guarded { acceptedAtByNonce.size }
}

fun deviceKeyHash(devicePublicKey: String): String = sha256Url(devicePublicKey)

/**
 * Checks a [DeviceProof] over [requestText] sent to [endpoint] for the session behind [ticket]: version, a fingerprint
 * matching the key, freshness, a nonce not seen before, and the signature. It says nothing about whether the device is
 * the one the session approved; the caller decides that.
 *
 * [sessionTokenHash] is the hash of the token that actually authenticated the request, so a device pairing by name
 * signs over its window-scoped pairing token and a device holding a ticket signs over the session token. Binding the
 * proof to the credential it arrived with is what stops one being replayed under the other.
 */
fun NonceStore.verifyDeviceProof(
    ticket: SessionTicket,
    endpoint: String,
    requestText: String,
    proof: DeviceProof,
    nowEpochMillis: Long,
    sessionTokenHash: String = sha256Url(ticket.token),
): LinkFaultCode? {
    // Checked before the signature: the version is part of every transcript, so a mismatch would otherwise read like a
    // forged signature rather than an out-of-date app.
    if (proof.securityVersion != ProtocolVersions.SECURITY_VERSION) return LinkFaultCode.PROTOCOL_VERSION_UNSUPPORTED
    if (proof.devicePublicKey.isBlank() || proof.nonce.isBlank() || proof.signature.isBlank()) {
        return LinkFaultCode.UNAUTHORIZED
    }
    if (proof.deviceFingerprint != deviceKeyHash(proof.devicePublicKey)) return LinkFaultCode.UNAUTHORIZED
    val skew = nowEpochMillis - proof.signedAtEpochMillis
    if (skew > LinkLimits.PROOF_FRESHNESS_MILLIS || -skew > LinkLimits.PROOF_FRESHNESS_MILLIS) {
        return LinkFaultCode.REPLAYED
    }
    if (!verifyDeviceSignature(ticket, endpoint, requestText, proof, sessionTokenHash)) {
        return LinkFaultCode.UNAUTHORIZED
    }
    if (!accept("${proof.deviceFingerprint}:${proof.nonce}", nowEpochMillis)) return LinkFaultCode.REPLAYED
    return null
}

fun verifyDeviceSignature(
    ticket: SessionTicket,
    endpoint: String,
    requestText: String,
    proof: DeviceProof,
    sessionTokenHash: String = sha256Url(ticket.token),
): Boolean =
    runCatching {
        verifyEcdsaP256Sha256(
            spki = proof.devicePublicKey.decodeBase64Url(),
            message = deviceProofMessage(ticket, endpoint, requestText, proof, sessionTokenHash),
            derSignature = proof.signature.decodeBase64Url(),
        )
    }.getOrDefault(false)

fun deviceProofMessage(
    ticket: SessionTicket,
    endpoint: String,
    requestText: String,
    proof: DeviceProof,
    sessionTokenHash: String = sha256Url(ticket.token),
): ByteArray =
    SecurityTranscripts.deviceProof(
        endpoint = endpoint,
        requestHash = sha256Url(requestText),
        sessionId = ticket.sessionId,
        sessionTokenHash = sessionTokenHash,
        companionFingerprint = ticket.fingerprint,
        devicePublicKey = proof.devicePublicKey,
        nonce = proof.nonce,
        signedAtEpochMillis = proof.signedAtEpochMillis,
    ).encodeToByteArray()
