package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingConfirmationResponse
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.contract.SessionConfirmPairingRequest
import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.pairingDebug
import app.tennyo.mobile.link.transport.LinkHttp

/** First-time pairing: proving the tapped words and storing the pair material the IDE sends back. */
class FirstPairing(
    private val http: LinkHttp,
    private val deviceIdentity: DeviceIdentity,
    private val rememberedPairs: RememberedPairStore,
    private val knownHosts: KnownHosts,
) {
    /**
     * Confirms a tapped word selection.
     *
     * [selectedWords] is ordered by tap, and the order is part of the secret — never sort or de-duplicate it here.
     * The selection MACs the transcript; the pair material comes back under a key derived from the ephemeral ECDH
     * exchange instead, so a recorded exchange cannot be unwrapped by grinding the possible selections offline.
     */
    suspend fun confirm(
        ticket: LinkTicket,
        selectedWords: List<String>,
        challenge: PairingChallenge,
    ): PairingConfirmation {
        val identity = deviceIdentity.snapshot()
        val attemptId = deviceIdentity.nextAttemptId()
        EphemeralKeyAgreement().use { keyAgreement ->
            val attempt = PairingAttempt(
                artifact = ticket,
                route = LinkEndpoints.PAIR,
                devicePublicKey = identity.publicKeyBase64,
                deviceFingerprint = identity.fingerprint,
                attemptId = attemptId,
                serverChallenge = challenge.serverChallenge,
                clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
            )
            val request = PairRequest.Confirm(
                SessionConfirmPairingRequest(
                    sessionId = ticket.sessionId,
                    devicePublicKey = identity.publicKeyBase64,
                    deviceFingerprint = identity.fingerprint,
                    challengeId = challenge.challengeId,
                    attemptId = attemptId,
                    clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                    clientPairingProof = attempt.proof(challenge.gridSeed, selectedWords),
                ),
            )
            pairingDebug {
                "Confirming pairing selection: ${selectedWords.size} of ${challenge.wordGrid.size} tiles, " +
                    "challenge ${challenge.challengeId.debugShortId()}, attempt ${attemptId.debugShortId()}"
            }
            val response = http.pair(ticket, request) {
                LinkJson.decodeFromString(PairingConfirmationResponse.serializer(), it)
            }
            pairingDebug { "Pairing confirmation returned ${response.status}" }
            if (response.status != PairingConfirmationStatus.Success) {
                return PairingConfirmation(response)
            }
            val responseKey = keyAgreement.responseKey(
                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
                serverChallenge = challenge.serverChallenge,
                transcriptHash = attempt.transcriptHash(),
            )
            pairingDebug { "Pairing key exchange " + if (responseKey == null) "failed" else "completed" }
            // A device that paired by name holds only the window's credential, so the real ticket arrives sealed
            // under the key this exchange agreed. A device that already had a ticket keeps the one it came with.
            val paired = responseKey
                ?.let { key -> response.encryptedArtifact?.let { sealed -> openTicket(sealed, key) } }
                ?: ticket
            responseKey
                ?.let { key -> response.encryptedPairMaterial?.let { material -> openPairMaterial(material, key) } }
                ?.let { material -> rememberedPairs.save(paired, material) }
            response.session?.let { knownHosts.remember(paired, it) }
            return PairingConfirmation(response, paired)
        }
    }
}

/**
 * A confirmation and the ticket it leaves this device holding: the one it came with when it already had a session
 * token, or the one the host sealed into the answer when it paired by name.
 */
data class PairingConfirmation(
    val response: PairingConfirmationResponse,
    val ticket: LinkTicket? = null,
) {
    val status: PairingConfirmationStatus get() = response.status
    val pairingChallenge: PairingChallenge? get() = response.pairingChallenge
}
