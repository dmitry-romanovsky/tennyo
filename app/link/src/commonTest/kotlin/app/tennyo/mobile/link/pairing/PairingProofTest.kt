package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.SessionResponse
import app.tennyo.mobile.link.LinkTicket
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Client-side halves of the pairing exchange. The shared derivations are covered in `:shared:crypto`; these cover how
 * the client wires its attempt into them.
 */
class PairingProofTest {

    @Test
    fun `pairing proof is bound to both ephemeral keys`() {
        val proof = proof(clientKey = "client-key", companionKey = "companion-key")

        assertNotEquals(proof, proof(clientKey = "other-client-key", companionKey = "companion-key"))
        assertNotEquals(proof, proof(clientKey = "client-key", companionKey = "other-companion-key"))
    }

    @Test
    fun `pairing proof depends on tap order`() {
        assertNotEquals(
            proof(clientKey = "client-key", companionKey = "companion-key"),
            proof(clientKey = "client-key", companionKey = "companion-key", words = WORDS.reversed()),
        )
    }

    @Test
    fun `session response without a word grid is rejected`() {
        assertFailsWith<SerializationException> {
            Json { ignoreUnknownKeys = true }.decodeFromString<SessionResponse>(
                """
                  {
                    "sessionId": "s",
                    "projectName": "Project",
                    "projectPath": "/tmp/Project",
                    "capabilities": ["FILES"],
                    "stats": {},
                    "requiresPairingConfirmation": true,
                    "pairingChallenge": {
                      "challengeId": "challenge-id",
                      "serverChallenge": "server-challenge",
                      "expiresAtEpochMillis": 1000
                    },
                    "serverTimeEpochMillis": 900,
                    "expiresAtEpochMillis": 1000
                  }
                """.trimIndent()
            )
        }
    }

    private fun proof(
        clientKey: String,
        companionKey: String,
        words: List<String> = WORDS,
    ): String =
        PairingAttempt(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = "device-key",
            deviceFingerprint = "device-fingerprint",
            attemptId = "attempt-1",
            serverChallenge = "server-challenge",
            clientEphemeralPublicKey = clientKey,
            companionEphemeralPublicKey = companionKey,
        ).proof(GRID_SEED, words)

    private companion object {
        const val GRID_SEED = "grid-seed-1"
        val WORDS = listOf("Zephyr", "Cedar", "Maple", "Anchor")
        val ARTIFACT = LinkTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
        )
    }
}
