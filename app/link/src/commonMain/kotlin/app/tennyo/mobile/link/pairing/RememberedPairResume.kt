package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.RememberedPairResumeRequest
import app.tennyo.contract.RememberedPairResumeResponse
import app.tennyo.contract.RememberedPairResumeStatus
import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.transport.LinkHttp

/** Answering a pairing challenge with a pair remembered from an earlier session, so no words are needed. */
class RememberedPairResume(
    private val http: LinkHttp,
    private val deviceIdentity: DeviceIdentity,
    private val rememberedPairs: RememberedPairs,
    private val knownHosts: KnownHosts,
) {
    /** `null` when no pair is remembered for [ticket]; the caller then falls back to the pairing words. */
    suspend fun resume(ticket: LinkTicket, challenge: PairingChallenge): RememberedPairResumeResponse? {
        val pair = rememberedPairs.forResume(ticket) ?: return null
        val attemptId = deviceIdentity.nextAttemptId()
        EphemeralKeyAgreement().use { keyAgreement ->
            val attempt = RememberedPairAttempt(
                artifact = ticket,
                route = LinkEndpoints.PAIR,
                challengeId = challenge.challengeId,
                attemptId = attemptId,
                serverChallenge = challenge.serverChallenge,
                deviceFingerprint = deviceIdentity.fingerprint,
                clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
            )
            val request = PairRequest.ResumePair(
                RememberedPairResumeRequest(
                    sessionId = ticket.sessionId,
                    pairId = pair.pairId,
                    challengeId = challenge.challengeId,
                    attemptId = attemptId,
                    clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                    clientPairProof = attempt.proof(pair.pairSecret),
                ),
            )
            val response = http.pair(ticket, request) {
                LinkJson.decodeFromString(RememberedPairResumeResponse.serializer(), it)
            }
            when (response.status) {
                RememberedPairResumeStatus.Success -> {
                    response.session?.let { knownHosts.remember(ticket, it) }
                    // pairSecret proved who we are; the rotated material arrives under the ECDH key instead, so the
                    // current secret alone cannot unwrap a recorded rotation later.
                    response.encryptedRotatedPair
                        ?.let { rotated ->
                            keyAgreement.responseKey(
                                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
                                serverChallenge = challenge.serverChallenge,
                                transcriptHash = attempt.transcriptHash(),
                            )?.let { key -> openPairMaterial(rotated, key) }
                        }
                        ?.let { material -> rememberedPairs.store.save(ticket, material) }
                }

                RememberedPairResumeStatus.Rejected -> rememberedPairs.store.remove(ticket)
            }
            return response
        }
    }
}
