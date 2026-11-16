package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalTabsStateTest {
    @Test
    fun terminalMetadataUsesBackendSelectedTabWhenMobileSelectionConflicts() {
        val state = TerminalState(
            tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
            selectedTerminalId = "terminal-2",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
                selectedTerminalId = "terminal-1",
            )
        )

        assertEquals("terminal-1", updated.selectedTerminal?.id)
    }

    @Test
    fun terminalMetadataKeepsPendingMobileSelectionUntilIdeConfirms() {
        val state = TerminalState(
            tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
            selectedTerminalId = "terminal-2",
            pendingSelectionId = "terminal-2",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
                selectedTerminalId = "terminal-1",
            )
        )

        assertEquals("terminal-2", updated.selectedTerminal?.id)
        assertEquals("terminal-2", updated.pendingSelectionId)
    }

    @Test
    fun terminalMetadataClearsPendingMobileSelectionWhenIdeConfirms() {
        val state = TerminalState(
            tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
            selectedTerminalId = "terminal-2",
            pendingSelectionId = "terminal-2",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
                selectedTerminalId = "terminal-2",
            )
        )

        assertEquals("terminal-2", updated.selectedTerminal?.id)
        assertNull(updated.pendingSelectionId)
    }

    @Test
    fun terminalMetadataFallsBackWhenMobileSelectedTabClosed() {
        val state = TerminalState(
            tabs = listOf(terminalTab("terminal-closed")),
            selectedTerminalId = "terminal-closed",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab("terminal-1"), terminalTab("terminal-2")),
                selectedTerminalId = "terminal-2",
            )
        )

        assertEquals("terminal-2", updated.selectedTerminal?.id)
    }

    private fun terminalTab(id: String): TerminalTab =
        TerminalTab(id = id, title = id, cwd = "/project")
}
