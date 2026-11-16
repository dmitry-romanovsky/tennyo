package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RememberedPairProofTest {

    /**
     * The companion asserts this same literal in `LinkSecurityTest.remembered pair proof has a stable
     * device-bound transcript vector`. Both sides build the transcript independently, so this pair is what proves
     * they still agree.
     */
    @Test
    fun rememberedPairProofHasStableDeviceBoundTranscriptVector() {
        assertEquals(
            "24TSq2Ujkg0BXTRiLATSSvHckhOb2Jkf97UdchtcDOw",
            attempt().proof(pairSecret = "pair-secret"),
        )
    }

    /** Same literal as the companion's `discovery pair proof has a stable transcript vector`. */
    @Test
    fun discoveryPairProofHasStableTranscriptVector() {
        assertEquals(
            "-E8HAHX8sDGuL0sNdIcnAwaitpXeVhc4t7rhemIzmKY",
            DiscoveryAttempt(
                companionId = "companion-1",
                fingerprint = "tls-fingerprint",
                route = LinkEndpoints.DISCOVER,
                challengeId = "challenge-1",
                attemptId = "attempt-1",
                serverChallenge = "server-challenge-1",
                deviceFingerprint = "device-fingerprint",
                clientEphemeralPublicKey = "client-ephemeral",
                companionEphemeralPublicKey = "companion-ephemeral",
            ).proof(pairSecret = "pair-secret"),
        )
    }

    @Test
    fun rememberedPairProofIsBoundToBothEphemeralKeys() {
        val proof = attempt().proof(pairSecret = "pair-secret")

        // Without this binding a relay could substitute its own key and the exchange carrying the rotated pair
        // material would authenticate nothing.
        assertNotEquals(
            proof,
            attempt().copy(clientEphemeralPublicKey = "other-client-ephemeral").proof(pairSecret = "pair-secret"),
        )
        assertNotEquals(
            proof,
            attempt().copy(companionEphemeralPublicKey = "other-companion-ephemeral").proof(pairSecret = "pair-secret"),
        )
    }

    private fun attempt() = RememberedPairAttempt(
        artifact = LinkTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session-id",
            token = "session-token",
            fingerprint = "transport-fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
        ),
        route = LinkEndpoints.PAIR,
        challengeId = "challenge-1",
        attemptId = "attempt-1",
        serverChallenge = "server-challenge-1",
        deviceFingerprint = "device-fingerprint",
        clientEphemeralPublicKey = "client-ephemeral",
        companionEphemeralPublicKey = "companion-ephemeral",
    )
}
