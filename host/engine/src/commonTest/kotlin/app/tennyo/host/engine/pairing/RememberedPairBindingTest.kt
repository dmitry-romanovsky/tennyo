package app.tennyo.host.engine.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.session.SessionTicket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The store-independent half of remembered-pair security: device binding and the resume proof check. */
class RememberedPairBindingTest {
    @Test
    fun `a pair only resumes for the device key it was confirmed with`() {
        val record = record(deviceKeyHash = "device-a")

        assertTrue(record.isBoundToDevice("device-a"))
        assertFalse(record.isBoundToDevice("device-b"))
        assertFalse(record.isBoundToDevice(""))
    }

    @Test
    fun `proof check rejects a tampered, foreign, or oversized proof`() {
        val record = record(deviceKeyHash = "device-a")
        val attempt = attempt()
        val proof = rememberedPairProof(record.pairSecret, attempt)

        assertTrue(rememberedPairProofMatches(record, attempt, proof))
        assertFalse(rememberedPairProofMatches(record, attempt, proof.tampered()))
        assertFalse(rememberedPairProofMatches(record, attempt.copy(attemptId = "attempt-2"), proof))
        assertFalse(rememberedPairProofMatches(record, attempt, "A".repeat(10_000)))
    }

    /** Changes the last character; the replacement always differs so the check can never pass by chance. */
    private fun String.tampered(): String = dropLast(1) + if (last() == 'A') "B" else "A"

    private fun record(deviceKeyHash: String): RememberedPairRecord =
        newRememberedPair(deviceLabel = "Pixel", deviceKeyHash = deviceKeyHash, nowEpochMillis = NOW).second

    private fun attempt() = RememberedPairAttempt(
        artifact = SessionTicket(
            endpoint = "https://127.0.0.1",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Tennyo",
            projectPath = "/work/tennyo",
            capabilities = emptySet(),
        ),
        route = LinkEndpoints.PAIR,
        challengeId = "challenge-1",
        attemptId = "attempt-1",
        serverChallenge = "server-challenge",
        deviceFingerprint = "device-a",
        clientEphemeralPublicKey = "client-ephemeral",
        companionEphemeralPublicKey = "companion-ephemeral",
    )

    private companion object {
        const val NOW = 1_000_000L
    }
}
