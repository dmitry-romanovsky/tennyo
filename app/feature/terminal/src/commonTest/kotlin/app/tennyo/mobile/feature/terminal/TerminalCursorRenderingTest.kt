package app.tennyo.mobile.feature.terminal


import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCursorRenderingTest {
    @Test
    fun cursorAtNewlineBoundaryStaysOnPreviousRenderedRow() {
        val text = "prompt> first\nsecond"

        assertEquals(0, renderedLines(text, 13).cursorLineIndex())
    }

    @Test
    fun cursorAfterNewlineMovesToNextRenderedRow() {
        val text = "prompt> first\n\n"

        assertEquals(1, renderedLines(text, 14).cursorLineIndex())
        assertEquals(2, renderedLines(text, 14).size)
    }

    @Test
    fun cursorAtTerminalGridSeparatorStaysOnTypedSymbolsRow() {
        val typedSymbols = "!@#$%^&*()[]{}<>"
        val text = "prompt> $typedSymbols\n\n"
        val cursorOffset = text.indexOf('\n')

        assertEquals(0, renderedLines(text, cursorOffset).cursorLineIndex())
        assertEquals(1, renderedLines(text, cursorOffset).size)
    }

    @Test
    fun cursorAtLineEndStaysOnFinalRenderedRow() {
        val text = "prompt> first\nsecond"

        assertEquals(1, renderedLines(text, text.length).cursorLineIndex())
    }

    @Test
    fun trailingBlankRowsArePreservedWhenTheyContainCursor() {
        val text = "prompt> first\n\n"

        assertEquals(2, renderedLines(text, text.length).cursorLineIndex())
        assertEquals(3, renderedLines(text, text.length).size)
    }

    @Test
    fun trailingBlankRowsWithoutCursorAreTrimmed() {
        val text = "prompt> first\n\n"

        assertEquals(1, renderedLines(text, 5).size)
    }

    @Test
    fun hiddenCursorDoesNotAddSyntheticCursorCell() {
        val snapshot = TerminalSnapshot(
            text = "prompt> ",
            cursorOffset = "prompt> ".length,
            cursorVisible = false,
        )

        assertEquals(listOf("prompt>"), snapshot.toScreenLines().map { it.displayText })
    }

    @Test
    fun visibleCursorAddsSyntheticCursorCellAtLineEnd() {
        val snapshot = TerminalSnapshot(
            text = "prompt> ",
            cursorOffset = "prompt> ".length,
            cursorVisible = true,
        )

        assertEquals(listOf("prompt>  "), snapshot.toScreenLines().map { it.displayText })
    }

    @Test
    fun styledTrailingSpaceIsPreservedForApplicationPaintedCursor() {
        val snapshot = TerminalSnapshot(
            text = "> prompt ",
            cursorVisible = false,
            spans = listOf(
                TerminalStyleSpan(
                    start = "> prompt".length,
                    end = "> prompt ".length,
                    background = "#666666",
                )
            ),
        )

        assertEquals(listOf("> prompt "), snapshot.toScreenLines().map { it.displayText })
    }

    @Test
    fun styledTrailingBlankRowIsPreserved() {
        val snapshot = TerminalSnapshot(
            text = "prompt\n   ",
            cursorVisible = false,
            spans = listOf(TerminalStyleSpan(start = 7, end = 10, background = "#333333")),
        )

        assertEquals(listOf("prompt", "   "), snapshot.toScreenLines().map { it.displayText })
    }

    @Test
    fun claudeStylePromptRedrawKeepsCursorOnActiveInputRow() {
        val activePrompt = "> explain resize policy"
        val paddedActivePrompt = activePrompt.padEnd(activePrompt.length + 8)
        val text = listOf("Claude Code", "", paddedActivePrompt, "").joinToString("\n")
        val cursorOffset = text.indexOf(activePrompt) + activePrompt.length

        assertEquals(2, renderedLines(text, cursorOffset).cursorLineIndex())
    }

    private fun renderedLines(text: String, cursorOffset: Int): List<TerminalScreenLine> =
        TerminalSnapshot(text = text, cursorOffset = cursorOffset).toScreenLines()

    private fun List<TerminalScreenLine>.cursorLineIndex(): Int? = firstOrNull { it.cursorOffset != null }?.index
}
