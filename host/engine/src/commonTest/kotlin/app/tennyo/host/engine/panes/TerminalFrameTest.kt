package app.tennyo.host.engine.panes

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalFrameTest {
    @Test
    fun `plain terminal snapshot growth emits compact append frame`() {
        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(text = "prompt> "),
            snapshot = TerminalSnapshot(text = "prompt> ls\n"),
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 1L,
            revision = 2L,
        )

        assertEquals("terminal", frame.terminalId)
        assertEquals("", frame.text)
        assertEquals("ls\n", frame.append)
        assertFalse(frame.isError)
        assertNull(frame.snapshot)
        assertEquals(1L, frame.baseRevision)
        assertEquals(2L, frame.revision)
        assertEquals(true, frame.cursorVisible)
    }

    @Test
    fun `hidden cursor snapshot change emits terminal frame without text change`() {
        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(text = "prompt> ", cursorVisible = true),
            snapshot = TerminalSnapshot(text = "prompt> ", cursorVisible = false),
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 7L,
            revision = 8L,
        )

        assertEquals("", frame.text)
        assertEquals("", frame.append)
        assertEquals(false, frame.cursorVisible)
        assertNull(frame.snapshot)
        assertEquals(7L, frame.baseRevision)
        assertEquals(8L, frame.revision)
    }

    @Test
    fun `terminal frame without base revision emits compact full snapshot`() {
        val snapshot = TerminalSnapshot(
            text = "prompt> ls\n",
            spans = listOf(promptSpan()),
        )

        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(text = "prompt> "),
            snapshot = snapshot,
            styleRegistry = TerminalStyleRegistry(),
        )

        assertEquals("", frame.text)
        assertEquals("prompt> ls\n", frame.snapshot?.text)
        assertTrue(frame.snapshot?.spans.orEmpty().isEmpty())
        assertEquals(listOf(0, 7), frame.snapshot?.styleRuns?.single()?.ranges)
    }

    @Test
    fun `growth after styled terminal snapshot emits full snapshot`() {
        val previous = TerminalSnapshot(
            text = "prompt> ",
            spans = listOf(promptSpan()),
        )
        val snapshot = TerminalSnapshot(text = "prompt> ls\n")

        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = previous,
            snapshot = snapshot,
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 1L,
            revision = 2L,
        )

        assertEquals("", frame.append)
        assertEquals("", frame.text)
        assertEquals("prompt> ls\n", frame.snapshot?.text)
        assertTrue(frame.snapshot?.styleRuns.orEmpty().isEmpty())
    }

    @Test
    fun `styled compact terminal growth emits append frame`() {
        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(
                text = "prompt> ",
                spans = listOf(promptSpan()),
            ),
            snapshot = TerminalSnapshot(
                text = "prompt> ls\n",
                spans = listOf(promptSpan(), commandSpan(start = 8, end = 10)),
                cursorOffset = 11,
            ),
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 1L,
            revision = 2L,
        )

        assertEquals("", frame.text)
        assertEquals("ls\n", frame.append)
        assertEquals(1L, frame.baseRevision)
        assertEquals(2L, frame.revision)
        assertEquals(11, frame.cursorOffset)
        assertNull(frame.snapshot)
        assertEquals(listOf(0, 2), frame.styleRuns.single().ranges)
        assertEquals("#569CD6", frame.styles.values.single().foreground)
    }

    @Test
    fun `styled compact terminal growth omits cached style definitions`() {
        val registry = TerminalStyleRegistry()
        terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(text = "prompt> "),
            snapshot = TerminalSnapshot(
                text = "prompt> ls\n",
                spans = listOf(commandSpan(start = 8, end = 10)),
            ),
            styleRegistry = registry,
            baseRevision = 1L,
            revision = 2L,
        )

        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(
                text = "prompt> ls\n",
                spans = listOf(commandSpan(start = 8, end = 10)),
            ),
            snapshot = TerminalSnapshot(
                text = "prompt> ls\npwd\n",
                spans = listOf(commandSpan(start = 8, end = 10), commandSpan(start = 11, end = 14)),
            ),
            styleRegistry = registry,
            baseRevision = 2L,
            revision = 3L,
        )

        assertEquals("pwd\n", frame.append)
        assertTrue(frame.styles.isEmpty())
        assertEquals(listOf(0, 3), frame.styleRuns.single().ranges)
    }

    @Test
    fun `compact terminal frame can slide visible window`() {
        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(text = "abc"),
            snapshot = TerminalSnapshot(text = "bcde"),
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 4L,
            revision = 5L,
        )

        assertEquals(1, frame.dropPrefixChars)
        assertEquals("de", frame.append)
        assertNull(frame.snapshot)
    }

    @Test
    fun `compact terminal frame emits full snapshot when retained styles change`() {
        val snapshot = TerminalSnapshot(
            text = "prompt> ls\n",
            spans = listOf(commandSpan(start = 0, end = 7), commandSpan(start = 8, end = 10)),
        )

        val frame = terminalFrameForSnapshot(
            terminalId = "terminal",
            previous = TerminalSnapshot(
                text = "prompt> ",
                spans = listOf(promptSpan()),
            ),
            snapshot = snapshot,
            styleRegistry = TerminalStyleRegistry(),
            baseRevision = 1L,
            revision = 2L,
        )

        assertEquals("", frame.append)
        assertEquals("", frame.text)
        assertEquals("prompt> ls\n", frame.snapshot?.text)
        assertEquals(2L, frame.revision)
    }

    private fun promptSpan(): TerminalStyleSpan =
        TerminalStyleSpan(
            start = 0,
            end = 7,
            foreground = "#23D18B",
            background = null,
            bold = false,
            italic = false,
            underline = false,
        )

    private fun commandSpan(start: Int, end: Int): TerminalStyleSpan =
        TerminalStyleSpan(
            start = start,
            end = end,
            foreground = "#569CD6",
            background = null,
            bold = false,
            italic = false,
            underline = false,
        )
}
