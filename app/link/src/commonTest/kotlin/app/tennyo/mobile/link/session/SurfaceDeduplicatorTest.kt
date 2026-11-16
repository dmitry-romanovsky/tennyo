package app.tennyo.mobile.link.session

import app.tennyo.mobile.link.ActiveSurface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceDeduplicatorTest {
    @Test
    fun suppressesMetadataEchoesUntilTheSurfaceChanges() {
        val deduplicator = SurfaceDeduplicator()
        val editorSurface = ActiveSurface(panel = "FILES", selectedFilePath = "src/Main.kt")
        val terminalSurface = ActiveSurface(panel = "TERMINAL", selectedTerminalId = "terminal-1")

        assertTrue(deduplicator.shouldQueue(editorSurface))
        assertFalse(deduplicator.shouldQueue(editorSurface))

        deduplicator.markSent(editorSurface)

        assertFalse(deduplicator.shouldQueue(editorSurface))
        assertTrue(deduplicator.shouldQueue(terminalSurface))

        deduplicator.markNotSent(terminalSurface)

        assertTrue(deduplicator.shouldQueue(terminalSurface))

        deduplicator.reset()

        assertTrue(deduplicator.shouldQueue(editorSurface))
    }
}
