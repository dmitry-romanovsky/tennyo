package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability

/**
 * Where the workspace can be, and what it can open over that.
 *
 * A **destination** is a place the user works: it owns the screen and sits in the destination strip. A **panel** is
 * something they open over that work and close again, reached through the overflow. The split is what lets a host
 * that serves only Git show one destination and no strip at all (screen N4), while a host that serves everything
 * shows two destinations and one overflow button (screen N2).
 *
 * Destinations are always offered: one the host does not serve opens on the reason it is empty, so Chat and Git never
 * vanish from a host that lacks them. A panel the host does not serve is absent from the sheet.
 */
enum class WorkspaceDestination(val pane: WorkspacePaneKind) {
    CHAT(WorkspacePaneKind.CHAT),
    GIT(WorkspacePaneKind.GIT),
}

enum class WorkspacePanel(val pane: WorkspacePaneKind) {
    FILES(WorkspacePaneKind.FILES),
    TERMINAL(WorkspacePaneKind.TERMINAL),
    LAUNCH(WorkspacePaneKind.LAUNCH),
    ;

    val capability: Capability? get() = pane.capability

    companion object {
        /** The panels [capabilities] can serve, in sheet order. */
        fun servedBy(capabilities: Set<Capability>): List<WorkspacePanel> =
            entries.filter { it.capability in capabilities }
    }
}

/**
 * What the workspace chrome renders: the destination pills, and the overflow button beside them when the host serves
 * a panel. With a panel open the button carries that panel's own icon (screen N5), and no pill is highlighted — the
 * panel, not the destination under it, is on screen.
 */
data class WorkspaceChromeState(
    val destinations: List<WorkspaceDestination>,
    val selectedDestination: WorkspaceDestination?,
    val panels: List<WorkspacePanel>,
    val openPanel: WorkspacePanel?,
) {
    val overflowVisible: Boolean get() = panels.isNotEmpty()

    /** The pill drawn as selected: none while a panel covers the destination. */
    val highlightedDestination: WorkspaceDestination? get() = selectedDestination.takeIf { openPanel == null }

    companion object {
        fun of(
            capabilities: Set<Capability>,
            selectedDestination: WorkspaceDestination?,
            openPanel: WorkspacePanel?,
        ): WorkspaceChromeState {
            val destinations = WorkspaceDestination.entries
            val panels = WorkspacePanel.servedBy(capabilities)
            return WorkspaceChromeState(
                destinations = destinations,
                selectedDestination = selectedDestination?.takeIf { it in destinations } ?: destinations.firstOrNull(),
                panels = panels,
                openPanel = openPanel?.takeIf { it in panels },
            )
        }
    }
}
