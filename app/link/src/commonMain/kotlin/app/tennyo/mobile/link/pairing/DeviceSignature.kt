package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DeviceProof
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.sha256Url
import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.withContext

/**
 * The bytes the device key signs for one request to [endpoint], laid out by [SecurityTranscripts.deviceProof] exactly
 * as the host verifies them.
 */
internal fun deviceProofMessage(
    ticket: LinkTicket,
    endpoint: String,
    requestText: String,
    devicePublicKey: String,
    nonce: String,
    signedAtEpochMillis: Long,
): ByteArray =
    SecurityTranscripts.deviceProof(
        endpoint = endpoint,
        requestHash = sha256Url(requestText),
        sessionId = ticket.sessionId,
        sessionTokenHash = sha256Url(ticket.token),
        companionFingerprint = ticket.fingerprint,
        devicePublicKey = devicePublicKey,
        nonce = nonce,
        signedAtEpochMillis = signedAtEpochMillis,
    ).encodeToByteArray()

/** This device's proof over [requestText] sent to [endpoint] for [ticket], signed off the calling thread. */
internal suspend fun DeviceIdentity.proof(
    ticket: LinkTicket,
    endpoint: String,
    requestText: String,
    signedAtEpochMillis: Long,
): DeviceProof =
    withContext(cryptoDispatcher) {
        val nonce = newNonce()
        DeviceProof(
            devicePublicKey = publicKeyBase64,
            deviceFingerprint = fingerprint,
            nonce = nonce,
            signedAtEpochMillis = signedAtEpochMillis,
            signature = sign(
                deviceProofMessage(ticket, endpoint, requestText, publicKeyBase64, nonce, signedAtEpochMillis),
            ),
        )
    }
