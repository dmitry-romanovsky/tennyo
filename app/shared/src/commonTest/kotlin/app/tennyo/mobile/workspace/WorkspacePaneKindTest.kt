package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability
import app.tennyo.mobile.link.LinkProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspacePaneKindTest {

    @Test
    fun aSessionOpensOnChatWhateverTheHostServes() {
        listOf(
            setOf(Capability.FILES, Capability.TERMINAL, Capability.GIT),
            setOf(Capability.FILES),
            setOf(Capability.CHAT, Capability.GIT),
        ).forEach { capabilities ->
            val connected = reduce(
                WorkspaceState(sessionTicket = TEST_TICKET),
                WorkspaceMsg.ProjectChanged(LinkProject(capabilities = capabilities)),
            )

            assertEquals(WorkspacePaneKind.CHAT, connected.selectedPane, "$capabilities")
            assertEquals(WorkspaceDestination.CHAT, connected.lastDestination, "$capabilities")
            assertNull(connected.openPanel, "$capabilities")
        }
    }

    @Test
    fun anUnservedDestinationTheUserPickedStaysAcrossProjectUpdates() {
        val project = WorkspaceMsg.ProjectChanged(LinkProject(capabilities = setOf(Capability.FILES, Capability.GIT)))
        val connected = reduce(WorkspaceState(sessionTicket = TEST_TICKET), project)

        val onGit = reduce(connected, WorkspaceMsg.PaneSelected(WorkspacePaneKind.GIT))
        val updated = reduce(onGit, WorkspaceMsg.ProjectChanged(LinkProject(capabilities = setOf(Capability.FILES))))

        assertEquals(WorkspacePaneKind.GIT, updated.selectedPane)
        assertEquals(WorkspaceDestination.GIT, updated.lastDestination)
    }

    @Test
    fun aPanelTheHostStopsServingFallsBackToChat() {
        val connected = reduce(
            WorkspaceState(sessionTicket = TEST_TICKET),
            WorkspaceMsg.ProjectChanged(LinkProject(capabilities = setOf(Capability.LAUNCH_CONFIGURATIONS))),
        )
        val onLaunch = reduce(connected, WorkspaceMsg.PaneSelected(WorkspacePaneKind.LAUNCH))

        val updated = reduce(onLaunch, WorkspaceMsg.ProjectChanged(LinkProject(capabilities = setOf(Capability.FILES))))

        assertEquals(WorkspacePaneKind.CHAT, updated.selectedPane)
        assertNull(updated.openPanel)
    }
}
