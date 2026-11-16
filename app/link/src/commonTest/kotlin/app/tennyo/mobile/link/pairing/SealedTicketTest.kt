package app.tennyo.mobile.link.pairing

import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.secureRandomBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A device pairing by name never learns the session token: it arrives sealed under the key the pairing exchange
 * agreed. This is the phone's half of that — the host's is `:host:engine`'s own SealedTicketTest.
 */
class SealedTicketTest {

    @Test
    fun `the sealed ticket opens with the key the exchange agreed`() {
        val key = secureRandomBytes(KEY_BYTES)

        val opened = assertNotNull(openTicket(seal(TICKET_JSON, key), key))

        assertEquals("the-session-token", opened.token)
        assertEquals("https://192.0.2.1:8443", opened.endpoint)
        assertEquals("Tennyo", opened.projectName)
    }

    @Test
    fun `a ticket sealed under a different key is refused rather than half-read`() {
        val sealed = seal(TICKET_JSON, secureRandomBytes(KEY_BYTES))

        assertNull(openTicket(sealed, secureRandomBytes(KEY_BYTES)))
    }

    @Test
    fun `a tampered payload is refused`() {
        val key = secureRandomBytes(KEY_BYTES)
        val sealed = seal(TICKET_JSON, key)

        assertNull(openTicket(sealed.replace('a', 'b'), key))
    }

    private fun seal(json: String, key: ByteArray): String =
        ProtectedPayloads.seal(
            plaintext = json.encodeToByteArray(),
            responseKey = key,
            nonce = secureRandomBytes(NONCE_BYTES).joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(HEX_RADIX).padStart(2, '0')
            },
        )

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 16
        const val HEX_RADIX = 16

        /** Exactly what the host seals: its `SessionTicket`, as JSON. */
        val TICKET_JSON = """
            {
              "endpoint": "https://192.0.2.1:8443",
              "sessionId": "session-1",
              "token": "the-session-token",
              "fingerprint": "fingerprint-1",
              "projectName": "Tennyo",
              "projectPath": "~/tennyo",
              "capabilities": ["FILES"],
              "alternateEndpoints": []
            }
        """.trimIndent()
    }
}
