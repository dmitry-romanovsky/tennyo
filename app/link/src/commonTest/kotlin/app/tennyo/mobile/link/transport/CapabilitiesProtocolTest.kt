package app.tennyo.mobile.link.transport

import app.tennyo.contract.Capability
import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkPush
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The companion and the app ship together, so a capability the app does not know is a contract error. */
class CapabilitiesProtocolTest {

    @Test
    fun `unknown capability is rejected`() {
        assertFailsWith<SerializationException> {
            LinkJson.decodeFromString(
                HandshakeResponse.serializer(),
                """
                {
                  "sessionId":"session",
                  "projectName":"Tennyo",
                  "projectPath":"/project",
                  "capabilities":["FILES","AI_CHAT","TERMINAL"],
                  "serverTimeEpochMillis":1,
                  "expiresAtEpochMillis":2
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `malformed capability collection is rejected`() {
        assertFailsWith<SerializationException> {
            LinkJson.decodeFromString(LinkPush.serializer(), """{"op":"capabilities","capabilities":"FILES"}""")
        }
    }

    @Test
    fun `known capabilities round trip`() {
        val original: LinkPush = LinkPush.Capabilities(setOf(Capability.TERMINAL, Capability.GIT))

        val encoded = LinkJson.encodeToString(LinkPush.serializer(), original)

        assertEquals(original, LinkJson.decodeFromString(LinkPush.serializer(), encoded))
    }
}
