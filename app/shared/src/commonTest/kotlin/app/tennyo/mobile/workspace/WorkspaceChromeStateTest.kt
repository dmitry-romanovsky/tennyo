package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the workspace chrome offers on a given host. Chat and Git are always offered — an unserved one says why — while
 * a panel the host cannot serve is absent from the overflow.
 */
class WorkspaceChromeStateTest {

    @Test
    fun `a host that serves everything shows both destinations and an overflow`() {
        val chrome = chromeOf(Capability.entries.toSet())

        assertEquals(listOf(WorkspaceDestination.CHAT, WorkspaceDestination.GIT), chrome.destinations)
        assertEquals(
            listOf(WorkspacePanel.FILES, WorkspacePanel.TERMINAL, WorkspacePanel.LAUNCH),
            chrome.panels,
        )
        assertTrue(chrome.overflowVisible)
    }

    @Test
    fun `chat and git show even when unserved`() {
        val chrome = chromeOf(setOf(Capability.FILES))

        assertEquals(listOf(WorkspaceDestination.CHAT, WorkspaceDestination.GIT), chrome.destinations)
        assertEquals(listOf(WorkspacePanel.FILES), chrome.panels)
    }

    @Test
    fun `a host serving no panels has no overflow button`() {
        assertFalse(chromeOf(setOf(Capability.CHAT, Capability.GIT)).overflowVisible)
    }

    @Test
    fun `a panel over a destination highlights no pill`() {
        val chrome = WorkspaceChromeState.of(
            capabilities = Capability.entries.toSet(),
            selectedDestination = WorkspaceDestination.CHAT,
            openPanel = WorkspacePanel.TERMINAL,
        )

        assertEquals(WorkspaceDestination.CHAT, chrome.selectedDestination)
        assertNull(chrome.highlightedDestination)
        assertEquals(WorkspaceDestination.CHAT, chromeOf(Capability.entries.toSet()).highlightedDestination)
    }

    @Test
    fun `the overflow reports the open panel`() {
        val chrome = WorkspaceChromeState.of(
            capabilities = setOf(Capability.CHAT, Capability.GIT, Capability.TERMINAL),
            selectedDestination = WorkspaceDestination.CHAT,
            openPanel = WorkspacePanel.TERMINAL,
        )

        assertEquals(WorkspacePanel.TERMINAL, chrome.openPanel)
    }

    @Test
    fun `a panel the host does not serve is not reported as open`() {
        val chrome = WorkspaceChromeState.of(
            capabilities = setOf(Capability.CHAT, Capability.GIT),
            selectedDestination = WorkspaceDestination.CHAT,
            openPanel = WorkspacePanel.TERMINAL,
        )

        assertNull(chrome.openPanel)
    }

    @Test
    fun `every destination and panel maps to the pane that serves it`() {
        WorkspaceDestination.entries.forEach { destination ->
            assertEquals(destination, destination.pane.destination)
            assertNull(destination.pane.panel)
        }
        WorkspacePanel.entries.forEach { panel ->
            assertEquals(panel, panel.pane.panel)
            assertNull(panel.pane.destination)
        }
    }

    @Test
    fun `opening a panel keeps the destination to return to`() {
        val chatting = reduce(
            WorkspaceState(sessionTicket = TEST_TICKET),
            WorkspaceMsg.PaneSelected(WorkspacePaneKind.CHAT),
        )

        val withTerminal = reduce(
            reduce(chatting, WorkspaceMsg.PanelOpened(WorkspacePanel.TERMINAL)),
            WorkspaceMsg.PaneSelected(WorkspacePaneKind.TERMINAL),
        )

        assertEquals(WorkspacePanel.TERMINAL, withTerminal.openPanel)
        assertEquals(WorkspaceDestination.CHAT, withTerminal.lastDestination)
    }

    @Test
    fun `closing a panel clears it`() {
        val open = reduce(
            WorkspaceState(sessionTicket = TEST_TICKET),
            WorkspaceMsg.PanelOpened(WorkspacePanel.FILES),
        )

        assertNull(reduce(open, WorkspaceMsg.PanelClosed).openPanel)
    }

    private fun chromeOf(capabilities: Set<Capability>): WorkspaceChromeState =
        WorkspaceChromeState.of(capabilities, selectedDestination = null, openPanel = null)
}
