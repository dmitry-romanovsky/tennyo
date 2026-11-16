package app.tennyo.mobile.workspace

import app.tennyo.contract.SessionStats
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket

/** Every change to [WorkspaceState]; applied only through [reduce]. */
internal sealed interface WorkspaceMsg {
    data class LinkChanged(val link: LinkState, val ticket: LinkTicket?) : WorkspaceMsg

    /** The project or its capabilities changed; a pane the host no longer serves gives way to the first it does. */
    data class ProjectChanged(val project: LinkProject) : WorkspaceMsg
    data class StatsChanged(val stats: SessionStats) : WorkspaceMsg
    data object ConnectionMessageDismissed : WorkspaceMsg
    data class PaneSelected(val pane: WorkspacePaneKind) : WorkspaceMsg

    /** The session loaded its snapshot into the pane on screen; the others wait until they are shown. */
    data object SnapshotLoaded : WorkspaceMsg

    data class PanelOpened(val panel: WorkspacePanel) : WorkspaceMsg
    data object PanelClosed : WorkspaceMsg

    /** A pane action failed; shown apart from the connection until dismissed or the pane recovers. */
    data class ActionFailed(val text: UiText) : WorkspaceMsg
    data object ActionRecovered : WorkspaceMsg
    data object ActionFailureDismissed : WorkspaceMsg
}

internal fun reduce(state: WorkspaceState, msg: WorkspaceMsg): WorkspaceState = when (msg) {
    // A pane failure belongs to the connection it happened on; a reconnect starts clean.
    is WorkspaceMsg.LinkChanged -> state.withLink(msg.link, msg.ticket)
        .let { if (it.connectionState == ConnectionState.CONNECTED) it else it.copy(actionFailure = null) }
    is WorkspaceMsg.ProjectChanged -> {
        // A session opens on Chat, served or not: a destination the host does not serve says why it is empty. Later
        // a destination the user chose stays, while a panel the host stopped serving falls back to Chat.
        val selectedPane = state.selectedPane
            .takeIf { state.hasProject && (it.destination != null || it.capability in msg.project.capabilities) }
            ?: WorkspacePaneKind.CHAT
        state.copy(
            projectName = msg.project.name,
            projectPath = msg.project.path,
            capabilities = msg.project.capabilities,
            selectedPane = selectedPane,
            // A panel on screen is an open panel, so the strip highlights no destination under it.
            openPanel = selectedPane.panel,
            lastDestination = selectedPane.destination ?: state.lastDestination,
        )
    }

    is WorkspaceMsg.StatsChanged -> state.copy(stats = msg.stats)
    WorkspaceMsg.ConnectionMessageDismissed ->
        state.copy(dismissedConnectionMessageKind = state.connectionMessageKind())
    is WorkspaceMsg.PaneSelected -> state.copy(
        selectedPane = msg.pane,
        lastDestination = msg.pane.destination ?: state.lastDestination,
        snapshotDue = state.snapshotDue - msg.pane,
    )

    WorkspaceMsg.SnapshotLoaded -> state.copy(snapshotDue = WorkspacePaneKind.entries.toSet() - state.selectedPane)

    is WorkspaceMsg.PanelOpened -> state.copy(openPanel = msg.panel)
    WorkspaceMsg.PanelClosed -> state.copy(openPanel = null)
    is WorkspaceMsg.ActionFailed -> state.copy(actionFailure = msg.text)
    WorkspaceMsg.ActionRecovered, WorkspaceMsg.ActionFailureDismissed -> state.copy(actionFailure = null)
}

