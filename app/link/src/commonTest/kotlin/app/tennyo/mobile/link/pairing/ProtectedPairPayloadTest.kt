package app.tennyo.mobile.link.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The companion produced [PROTECTED_PAYLOAD] (its `protected pair payload is stable across modules` test pins the same
 * literal). If the client's keystream or MAC drifts, every remembered-pair rotation fails to open.
 */
class ProtectedPairPayloadTest {

    @Test
    fun decryptsTheCompanionsFrozenPayload() {
        assertEquals(
            RememberedPairMaterial(
                pairId = "pair-1",
                pairSecret = "pair-secret",
                expiresAtEpochMillis = 1_800_000_000_000,
                deviceLabel = "Pixel 9 Android 17",
                companionId = "companion-1",
            ),
            openPairMaterial(PROTECTED_PAYLOAD, RESPONSE_KEY),
        )
    }

    @Test
    fun rejectsATamperedCiphertext() {
        assertNull(openPairMaterial(PROTECTED_PAYLOAD.replace("xA-sw_", "xA-sw0"), RESPONSE_KEY))
    }

    @Test
    fun rejectsAnUnknownAlgorithm() {
        assertNull(openPairMaterial(PROTECTED_PAYLOAD.replace("aes-256-ctr+hmac-sha256", "none"), RESPONSE_KEY))
    }

    @Test
    fun rejectsTheWrongResponseKey() {
        assertNull(openPairMaterial(PROTECTED_PAYLOAD, "another-response-key-0123456789ab".encodeToByteArray()))
    }

    private companion object {
        val RESPONSE_KEY = "response-key-0123456789abcdef012".encodeToByteArray()

        // Keep byte-identical to the companion's copy of this constant.
        const val PROTECTED_PAYLOAD = """{"algorithm":"aes-256-ctr+hmac-sha256","nonce":"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY",""" +
                """"ciphertext":"xA-sw_ZJjfu9tJtjt2KqVILZOqhz95jUYalt4xq3ZJXzxz1HxZ2kGEXEu9oCzuO1AM1cb8P8TcPaBpUK1ufwst6KZKQJ""" +
                """8KF8leva3rpwzBZRMgrZfPH9wU_KCJGqmnRsFllxQSdBAAJlL1zk6smrrB6JsFFTYAuo4Q-znJCXoKiiowk0Gjf3fBa8vIRpfHZMBTM",""" +
                """"mac":"2-5PHxxXIjrpIlLWVimHcx9FufkZE0SttFnY8zqCfJ4"}"""
    }
}
