package app.tennyo.host.engine.session

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.ActiveSurface
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkPush
import app.tennyo.contract.TerminalFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushProtocolTest {
    @Test
    fun `push history bounds retained characters and requires a snapshot after eviction`() {
        val history = PushHistory(maxPushes = 10, maxChars = 6)

        history.append(SentPush(seq = 1, frameText = "abc"))
        history.append(SentPush(seq = 2, frameText = "def"))
        history.append(SentPush(seq = 3, frameText = "ghi"))

        assertTrue(history.resumeAfter(0).snapshotRequired)
        assertEquals(listOf(3L), history.resumeAfter(2).missed.map { it.seq })
    }

    @Test
    fun `an oversized push is sent live but never retained`() {
        val history = PushHistory(maxPushes = 10, maxChars = 3)

        history.append(SentPush(seq = 1, frameText = "oversized"))

        assertTrue(history.resumeAfter(0).snapshotRequired)
        assertFalse(history.resumeAfter(1).snapshotRequired)
        assertTrue(history.resumeAfter(1).missed.isEmpty())
    }

    @Test
    fun `a client with no resume point starts from a snapshot`() {
        val history = PushHistory(maxPushes = 10, maxChars = 100)
        history.append(SentPush(seq = 1, frameText = "abc"))

        assertTrue(history.resumeAfter(null).snapshotRequired)
        assertTrue(history.resumeAfter(null).missed.isEmpty())
    }

    @Test
    fun `active surface tracker suppresses duplicate mobile commands`() {
        val tracker = ActivePaneTracker()
        val editorSurface = ActiveSurface(
            panel = ActivePanels.FILES,
            selectedFilePath = "src/Main.kt",
        )
        val secondEditorSurface = ActiveSurface(
            panel = ActivePanels.FILES,
            selectedFilePath = "src/Other.kt",
        )
        val terminalSurface = ActiveSurface(
            panel = ActivePanels.TERMINAL,
            selectedTerminalId = "terminal-1",
        )

        assertTrue(tracker.queue(editorSurface))
        assertFalse(tracker.queue(editorSurface))
        assertTrue(tracker.applyQueued(editorSurface))
        assertEquals(editorSurface, tracker.get())

        assertFalse(tracker.queue(editorSurface))
        assertTrue(tracker.queue(terminalSurface))
        assertTrue(tracker.queue(secondEditorSurface))

        assertFalse(tracker.applyQueued(terminalSurface))
        assertTrue(tracker.applyQueued(secondEditorSurface))
        assertEquals(secondEditorSurface, tracker.get())
    }

    @Test
    fun `opened frame is stable channel json`() {
        val frame = LinkFrame.Opened(
            streamId = "stream-id",
            commandKeySeed = "command-key-seed",
            serverTimeEpochMillis = 1234L,
            snapshotRequired = false,
        )

        assertEquals(
            """{"op":"opened","streamId":"stream-id","commandKeySeed":"command-key-seed","serverTimeEpochMillis":1234,"snapshotRequired":false}""",
            frameJson(frame),
        )
    }

    @Test
    fun `terminal output push is stable channel json`() {
        val frame = LinkFrame.Push(
            seq = 44,
            push = LinkPush.TerminalOutput(TerminalFrame(terminalId = "terminal", text = "chunk\n", isError = false)),
        )

        assertEquals(
            """{"op":"push","seq":44,"push":{"op":"terminalFrame","frame":{"terminalId":"terminal","text":"chunk\n"}}}""",
            frameJson(frame),
        )
    }

    private fun frameJson(frame: LinkFrame): String = LinkJson.encodeToString(LinkFrame.serializer(), frame)
}
