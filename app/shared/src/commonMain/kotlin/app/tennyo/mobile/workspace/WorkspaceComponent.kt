package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability
import app.tennyo.contract.SessionStats
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.feature.chat.ChatPaneComponent
import app.tennyo.mobile.feature.files.FilesPaneComponent
import app.tennyo.mobile.feature.git.GitPaneComponent
import app.tennyo.mobile.feature.launch.LaunchPaneComponent
import app.tennyo.mobile.feature.terminal.TerminalPaneComponent
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.PairingConfirmation
import com.arkivanov.decompose.router.pages.ChildPages
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.flow.StateFlow

/** The workspace screen: one link session's connection chrome, pairing, and the panes over it. */
interface WorkspaceComponent {
    val state: StateFlow<WorkspaceState>

    /** Every pane, alive for the whole workspace; the selected one is on screen. */
    val panes: Value<ChildPages<WorkspacePaneKind, WorkspacePaneChild>>

    fun onIntent(intent: WorkspaceIntent)

    fun onBack()
}

sealed interface WorkspacePaneChild {
    data class Chat(val component: ChatPaneComponent) : WorkspacePaneChild
    data class Files(val component: FilesPaneComponent) : WorkspacePaneChild
    data class Launch(val component: LaunchPaneComponent) : WorkspacePaneChild
    data class Terminal(val component: TerminalPaneComponent) : WorkspacePaneChild
    data class Git(val component: GitPaneComponent) : WorkspacePaneChild
}

data class WorkspaceState(
    val sessionTicket: LinkTicket,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val liveSyncState: LiveSyncState = LiveSyncState.DISCONNECTED,
    val projectName: String = "",
    val projectPath: String = "",
    val capabilities: Set<Capability> = emptySet(),
    val stats: SessionStats = SessionStats(),
    val screenStep: WorkspaceStep = WorkspaceStep.Initiating,
    /** Chat until the host reports what it serves; [WorkspaceMsg.ProjectChanged] moves it to a served pane. */
    val selectedPane: WorkspacePaneKind = WorkspacePaneKind.CHAT,
    /** The panel open over the work, if any; `null` when the destination has the screen to itself. */
    val openPanel: WorkspacePanel? = null,
    /** Where closing a panel returns to. */
    val lastDestination: WorkspaceDestination = WorkspaceDestination.CHAT,
    /** Panes the last snapshot skipped because they were off screen; each takes its own when first shown. */
    val snapshotDue: Set<WorkspacePaneKind> = emptySet(),
    /** Changed files the Git destination wants noticed in the strip; `null` until Git has reported. */
    val changedFileCount: Int? = null,
    val pairingConfirmation: PairingConfirmation? = null,
    /** Why the connection is not simply live; never a pane's own failure. */
    val issue: WorkspaceIssue? = null,
    /** The last pane action that failed, shown apart from the connection. */
    val actionFailure: UiText? = null,
    val unexpectedConnectionError: UnexpectedConnectionError? = null,
    val dismissedConnectionMessageKind: ConnectionMessageKind? = null,
) {
    fun hasCapability(capability: Capability): Boolean = capability in capabilities

    /** The host has said what it serves at least once; before that nothing the user picked is a choice yet. */
    val hasProject: Boolean get() = capabilities.isNotEmpty()

    fun displayConnectionState(): ConnectionState =
        if (connectionState == ConnectionState.CONNECTED && liveSyncState == LiveSyncState.RECONNECTING) {
            ConnectionState.RECONNECTING
        } else {
            connectionState
        }

    fun connectionMessageKind(): ConnectionMessageKind =
        if (issue != null) {
            ConnectionMessageKind.ERROR
        } else {
            when (displayConnectionState()) {
                ConnectionState.CONNECTED -> ConnectionMessageKind.CONNECTED
                ConnectionState.CONNECTING -> ConnectionMessageKind.CONNECTING
                ConnectionState.RECONNECTING -> ConnectionMessageKind.RECONNECTING
                ConnectionState.NEEDS_NEW_LINK -> ConnectionMessageKind.NEEDS_NEW_LINK
                ConnectionState.FAILED -> ConnectionMessageKind.FAILED
                ConnectionState.DISCONNECTED -> ConnectionMessageKind.DISCONNECTED
            }
        }
}

/** How the workspace chrome shows the connection; projected from [app.tennyo.mobile.link.LinkState]. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    NEEDS_NEW_LINK,
    FAILED,
}

enum class LiveSyncState {
    DISCONNECTED,
    CONNECTING,
    READY,
    RECONNECTING,
    FAILED,
}

enum class WorkspaceStep {
    Initiating,
    WaitingForIde,
    Pairing,
    Connected,
    Unavailable,
}

enum class ConnectionMessageKind {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    NEEDS_NEW_LINK,
    FAILED,
    DISCONNECTED,
    ERROR,
}

/** A failure the app did not anticipate: shown as [issue], with the stack trace behind "Show full error". */
data class UnexpectedConnectionError(
    val issue: WorkspaceIssue,
    val stackTrace: String,
)

/**
 * Every pane the workspace keeps alive. The name is what the host is told is on screen.
 *
 * A pane is reached either as a [WorkspaceDestination] — a place the user works, in the destination strip — or as a
 * [WorkspacePanel] they open over that work through the overflow. This enum stays the pane identity behind both.
 */
enum class WorkspacePaneKind(
    val title: String,
    val capability: Capability?,
) {
    CHAT("Chat", Capability.CHAT),
    FILES("Files", Capability.FILES),
    LAUNCH("Launch", Capability.LAUNCH_CONFIGURATIONS),
    TERMINAL("Terminal", Capability.TERMINAL),
    GIT("Git", Capability.GIT),
    ;

    /** The destination this pane is, if it is one. */
    val destination: WorkspaceDestination? get() = WorkspaceDestination.entries.firstOrNull { it.pane == this }

    /** The panel this pane is, if it is one. */
    val panel: WorkspacePanel? get() = WorkspacePanel.entries.firstOrNull { it.pane == this }
}

sealed interface WorkspaceIntent {
    data object RetryConnection : WorkspaceIntent
    data object EndSession : WorkspaceIntent
    data object RefreshSessionStats : WorkspaceIntent
    data class StatsVisibilityChanged(val open: Boolean) : WorkspaceIntent
    data object DismissConnectionMessage : WorkspaceIntent
    data object DismissActionFailure : WorkspaceIntent
    data class TogglePairingWord(val word: String) : WorkspaceIntent
    data object ClearPairingWords : WorkspaceIntent
    data object ConfirmPairing : WorkspaceIntent
    data class SelectPane(val pane: WorkspacePaneKind) : WorkspaceIntent
    data class SelectDestination(val destination: WorkspaceDestination) : WorkspaceIntent
    data class OpenPanel(val panel: WorkspacePanel) : WorkspaceIntent
    data object ClosePanel : WorkspaceIntent
}

