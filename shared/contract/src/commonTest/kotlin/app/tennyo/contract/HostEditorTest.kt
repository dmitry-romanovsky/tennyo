package app.tennyo.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HostEditorTest {
    @Test
    fun `the editor round trips on the wire`() {
        val handshake = handshake(editorJson = "\"devin_desktop\"")

        assertEquals(HostEditor.DEVIN_DESKTOP, handshake.editor)
        val encoded = LinkJson.encodeToString(HandshakeResponse.serializer(), handshake)
        assertEquals(handshake, LinkJson.decodeFromString(HandshakeResponse.serializer(), encoded))
    }

    @Test
    fun `an editor from a newer host reads as absent instead of failing the handshake`() {
        assertNull(handshake(editorJson = "\"zed\"").editor)
    }

    @Test
    fun `a host that names no editor leaves it absent`() {
        assertNull(handshake(editorJson = null).editor)
    }

    @Test
    fun `an absent editor is left off the wire`() {
        val encoded = LinkJson.encodeToString(HandshakeResponse.serializer(), handshake(editorJson = null))

        assertFalse("editor" in encoded)
        assertNull(handshake(editorJson = "null").editor)
    }

    private fun handshake(editorJson: String?): HandshakeResponse =
        LinkJson.decodeFromString(
            HandshakeResponse.serializer(),
            """
            {
              "sessionId":"session",
              "projectName":"Tennyo",
              "projectPath":"/project",
              ${editorJson?.let { "\"editor\":$it," }.orEmpty()}
              "capabilities":["FILES"],
              "serverTimeEpochMillis":1,
              "expiresAtEpochMillis":2
            }
            """.trimIndent(),
        )
}
