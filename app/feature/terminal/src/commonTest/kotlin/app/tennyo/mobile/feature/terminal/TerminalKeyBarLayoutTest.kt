package app.tennyo.mobile.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalKeyBarLayoutTest {
    @Test
    fun fixedColumnsPairKeysWithTheirLatchesAndEmphasizeOnlyEnter() {
        val layout = terminalKeyBarLayout(TerminalKeyBarSettings(), rows = 2)

        assertEquals(
            listOf(
                listOf("Esc", "Shift"),
                listOf("Tab", "Ctrl"),
                listOf("^C", "Alt"),
                listOf("Home", "←"),
                listOf("↑", "↓"),
                listOf("End", "→"),
                listOf("⌫", "⏎"),
            ),
            layout.fixedColumns.map { column -> column.map { it.label() } },
        )
        assertEquals(
            listOf("⏎"),
            layout.fixedColumns.flatten()
                .filterIsInstance<TerminalKeyBarItem.Key>()
                .filter { it.emphasized }
                .map { it.label },
        )
    }

    @Test
    fun defaultToggleableColumnsHoldPageKeysThenAdd() {
        val layout = terminalKeyBarLayout(TerminalKeyBarSettings(), rows = 2)

        assertEquals(listOf(listOf("PgUp", "PgDn"), listOf("+")), layout.toggleableColumns.labels())
    }

    @Test
    fun hiddenBuiltInsAndDisabledHotkeysLeaveTheirSlots() {
        val (c1, c2, c3, disabled) = hotkeys(4)
        val settings = TerminalKeyBarSettings(
            hiddenBuiltInKeys = setOf(ToggleableTerminalKey.PageDown),
            customHotkeys = listOf(c1, disabled.copy(enabled = false), c2, c3),
        )

        val layout = terminalKeyBarLayout(settings, rows = 2)

        assertEquals(
            listOf(
                listOf("PgUp", c1.chord.label()),
                listOf(c2.chord.label(), c3.chord.label()),
                listOf("+"),
            ),
            layout.toggleableColumns.labels(),
        )
    }

    @Test
    fun addStartsANewColumnAfterAnEvenNumberOfKeys() {
        // PgUp, PgDn, and two custom hotkeys fill two full columns.
        val settings = TerminalKeyBarSettings(customHotkeys = hotkeys(2))

        val columns = terminalKeyBarLayout(settings, rows = 2).toggleableColumns

        assertEquals(listOf(TerminalKeyBarItem.AddHotkey), columns.last())
        assertEquals(3, columns.size)
    }

    @Test
    fun singleRowKeepsTheSameOrder() {
        val settings = TerminalKeyBarSettings(customHotkeys = hotkeys(3))
        val twoRows = terminalKeyBarLayout(settings, rows = 2)

        val oneRow = terminalKeyBarLayout(settings, rows = 1)

        assertEquals(true, (oneRow.fixedColumns + oneRow.toggleableColumns).all { it.size == 1 })
        assertEquals(twoRows.fixedColumns.flatten(), oneRow.fixedColumns.flatten())
        assertEquals(twoRows.toggleableColumns.flatten(), oneRow.toggleableColumns.flatten())
    }

    @Test
    fun stableKeysAreUnique() {
        val layout = terminalKeyBarLayout(TerminalKeyBarSettings(customHotkeys = hotkeys(MAX_CUSTOM_TERMINAL_HOTKEYS)), 2)

        val keys = (layout.fixedColumns + layout.toggleableColumns).flatten().map { it.stableKey }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun columnMajorChunkingFillsColumnsTopToBottom() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), listOf(1, 2, 3, 4, 5).chunkedColumnMajor(2))
    }

    private fun List<List<TerminalKeyBarItem>>.labels(): List<List<String>> =
        map { column -> column.map { it.label() } }

    private fun TerminalKeyBarItem.label(): String =
        when (this) {
            is TerminalKeyBarItem.Key -> label
            is TerminalKeyBarItem.Latch -> modifier.label
            TerminalKeyBarItem.AddHotkey -> "+"
        }
}
