package app.tennyo.host.engine.panes

import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/** An emulator's screen grid, read into the snapshot the phone draws. */
class TerminalGridTest {
    private val red = TerminalStyleDefinition(foreground = "#CD3131", bold = true)

    @Test
    fun `rows join into text with styled runs as spans and the cursor where it stands`() {
        val snapshot = terminalGridSnapshot(
            rows = listOf(
                TerminalRow(listOf(TerminalRun("PS C:\\> ", null), TerminalRun("error", red), TerminalRun("    ", null))),
                TerminalRow(listOf(TerminalRun("> ", null))),
            ),
            cursorRow = 1,
            cursorColumn = 2,
        )

        assertEquals("PS C:\\> error\n> ", snapshot.text)
        assertEquals(listOf(TerminalStyleSpan(8, 13, foreground = "#CD3131", bold = true)), snapshot.spans)
        assertEquals(16, snapshot.cursorOffset)
    }

    @Test
    fun `blank rows below the cursor are dropped and a cursor past the text is padded to`() {
        val snapshot = terminalGridSnapshot(
            rows = listOf(TerminalRow(listOf(TerminalRun("ab", null))), TerminalRow(emptyList()), TerminalRow(emptyList())),
            cursorRow = 0,
            cursorColumn = 4,
        )

        assertEquals("ab  ", snapshot.text)
        assertEquals(4, snapshot.cursorOffset)
    }

    @Test
    fun `a long scrollback keeps its newest whole rows`() {
        val rows = (1..10).map { TerminalRow(listOf(TerminalRun("row$it", null))) }

        val snapshot = terminalGridSnapshot(rows, cursorRow = 9, cursorColumn = 5, maxChars = 12)

        assertEquals("row9\nrow10", snapshot.text)
        assertEquals(10, snapshot.cursorOffset)
    }

    @Test
    fun `palette colours follow the xterm table`() {
        assertEquals("#CD3131", ansiPaletteColor(1))
        assertEquals("#E5E5E5", ansiPaletteColor(15))
        assertEquals("#5F87AF", ansiPaletteColor(67))
        assertEquals("#808080", ansiPaletteColor(244))
    }
}
