package app.tennyo.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkFramePartsTest {
    @Test
    fun `a long frame is never cut inside a surrogate pair`() {
        // The emoji straddles the first slice boundary, so a plain cut would leave half of it in each part.
        val text = "a".repeat(LinkLimits.MAX_FRAME_CHARS / 2 - 1) + "😀" + "b".repeat(LinkLimits.MAX_FRAME_CHARS)

        val parts = linkFrameMessages(text) { 7L }.map { LinkJson.decodeFromString(LinkFrame.serializer(), it) }
            .map { it as LinkFrame.Part }

        assertTrue(parts.none { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
        assertEquals(text, parts.joinToString("") { it.text })
    }
}
