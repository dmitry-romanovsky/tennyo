package app.tennyo.host.engine.pairing

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkJson
import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.host.engine.session.SessionTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The session token never leaves the host in the clear: a device that pairs by name receives its ticket sealed
 * under the key the pairing exchange agreed, so a relay that saw the whole exchange still cannot read it.
 */
class SealedTicketTest {

    @Test
    fun `the sealed ticket opens with the pairing key`() {
        val key = secureRandomBytes(KEY_BYTES)

        val opened = ProtectedPayloads.open(protectSessionTicket(TICKET, key), key)

        assertEquals(TICKET.token, ticketToken(assertNotNull(opened)))
    }

    @Test
    fun `the sealed ticket does not open with any other key`() {
        val sealed = protectSessionTicket(TICKET, secureRandomBytes(KEY_BYTES))

        assertNull(ProtectedPayloads.open(sealed, secureRandomBytes(KEY_BYTES)))
    }

    private fun ticketToken(plaintext: ByteArray): String =
        LinkJson.decodeFromString(SessionTicket.serializer(), plaintext.decodeToString()).token

    private companion object {
        const val KEY_BYTES = 32
        val TICKET = SessionTicket(
            endpoint = "https://192.0.2.1:8443",
            sessionId = "session",
            token = "the-session-token",
            fingerprint = "fingerprint",
            projectName = "Tennyo",
            projectPath = "~/tennyo",
            companionId = "companion",
            hostVersion = "1.0",
            capabilities = setOf(Capability.FILES),
            alternateEndpoints = emptyList(),
        )
    }
}
