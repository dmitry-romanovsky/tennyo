package app.tennyo.crypto

import app.tennyo.contract.PROTECTED_PAYLOAD_ALGORITHM
import app.tennyo.contract.ProtocolVersions

/**
 * The exact strings each side of the protocol signs or MACs.
 *
 * Both sides must produce identical bytes, so the strings are defined once here. The frozen vectors in
 * `SecurityTranscriptVectorTest` stop the definition itself from moving.
 *
 * Every function is pure string layout. Anything hashed is hashed by the caller and passed in already encoded
 * (unpadded base64url SHA-256, see [sha256Url]).
 *
 * The protocol version is a transcript line rather than a `-vN` suffix on a label: bumping
 * [ProtocolVersions.SECURITY_VERSION] re-derives every proof, and label and number cannot drift apart.
 */
object SecurityTranscripts {

    /**
     * A device's proof over one pairing request or channel open: the endpoint, the exact request text (hashed), the
     * session and its host, the device key, and a nonce with the signing time. The host accepts it only while fresh.
     */
    fun deviceProof(
        endpoint: String,
        requestHash: String,
        sessionId: String,
        sessionTokenHash: String,
        companionFingerprint: String,
        devicePublicKey: String,
        nonce: String,
        signedAtEpochMillis: Long,
    ): String =
        lines(
            "tennyo-device-proof",
            endpoint,
            requestHash,
            sessionId,
            sessionTokenHash,
            companionFingerprint,
            devicePublicKey,
            nonce,
            signedAtEpochMillis.toString(),
        )

    /**
     * First-time pairing, MAC'd with the tapped-word selection secret.
     *
     * Both ephemeral public keys are in the transcript on purpose: leave either out and a relay can substitute its own
     * key, agree a shared secret with each side, and the pairing succeeds while authenticating nothing.
     */
    fun pairingConnect(
        sessionId: String,
        sessionTokenHash: String,
        companionFingerprint: String,
        route: String,
        devicePublicKey: String,
        deviceFingerprint: String,
        attemptId: String,
        serverChallenge: String,
        companionEphemeralPublicKey: String,
        clientEphemeralPublicKey: String,
    ): String =
        lines(
            "tennyo-pairing-connect",
            sessionId,
            sessionTokenHash,
            companionFingerprint,
            route,
            devicePublicKey,
            deviceFingerprint,
            attemptId,
            serverChallenge,
            companionEphemeralPublicKey,
            clientEphemeralPublicKey,
        )

    /**
     * Remembered-pair resume, MAC'd with `pairSecret`. The ephemeral keys make `pairSecret` *authenticate* the ECDH
     * exchange carrying the rotated material rather than encrypt it, so a leaked secret cannot unwrap recorded
     * rotations.
     */
    fun rememberedPairResume(
        route: String,
        challengeId: String,
        serverChallenge: String,
        sessionId: String,
        sessionTokenHash: String,
        companionFingerprint: String,
        deviceFingerprint: String,
        attemptId: String,
        companionEphemeralPublicKey: String,
        clientEphemeralPublicKey: String,
    ): String =
        lines(
            "tennyo-remembered-pair-resume",
            route,
            challengeId,
            serverChallenge,
            sessionId,
            sessionTokenHash,
            companionFingerprint,
            deviceFingerprint,
            attemptId,
            companionEphemeralPublicKey,
            clientEphemeralPublicKey,
        )

    /** The discovery-connect twin of [rememberedPairResume]; the same binding rules apply. */
    fun discoveryConnect(
        route: String,
        companionId: String,
        companionFingerprint: String,
        deviceFingerprint: String,
        challengeId: String,
        serverChallenge: String,
        attemptId: String,
        companionEphemeralPublicKey: String,
        clientEphemeralPublicKey: String,
    ): String =
        lines(
            "tennyo-discovery-connect",
            route,
            companionId,
            companionFingerprint,
            deviceFingerprint,
            challengeId,
            serverChallenge,
            attemptId,
            companionEphemeralPublicKey,
            clientEphemeralPublicKey,
        )

    /** One call on the channel, MAC'd with the per-stream command key. [callJsonHash] covers the call's JSON. */
    fun callMac(
        sessionId: String,
        sessionTokenHash: String,
        companionFingerprint: String,
        approvedDeviceFingerprint: String,
        streamId: String,
        sequence: Long,
        callId: Long,
        callJsonHash: String,
    ): String =
        lines(
            "tennyo-link-call",
            sessionId,
            sessionTokenHash,
            companionFingerprint,
            approvedDeviceFingerprint,
            streamId,
            sequence.toString(),
            callId.toString(),
            callJsonHash,
        )

    /**
     * Encrypt-then-MAC input for a [PROTECTED_PAYLOAD_ALGORITHM] payload. The algorithm label carries no version
     * suffix, so the version line here is what covers it; it is also a serialized wire value compared on receipt.
     */
    fun protectedPayloadMac(nonce: String, ciphertext: String): String =
        listOf(
            PROTECTED_PAYLOAD_ALGORITHM,
            ProtocolVersions.SECURITY_VERSION.toString(),
            nonce,
            ciphertext,
        ).joinToString("\n")

    private fun lines(label: String, vararg fields: String): String =
        (listOf(label, ProtocolVersions.SECURITY_VERSION.toString()) + fields).joinToString("\n")

}
