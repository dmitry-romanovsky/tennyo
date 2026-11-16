package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability
import app.tennyo.mobile.design.PaneNotice
import app.tennyo.mobile.feature.chat.RealChatPaneComponent
import app.tennyo.mobile.feature.files.FilesIntent
import app.tennyo.mobile.feature.files.RealFilesPaneComponent
import app.tennyo.mobile.feature.git.IdeGitSource
import app.tennyo.mobile.feature.git.LinkGitPane
import app.tennyo.mobile.feature.git.RealGitPaneComponent
import app.tennyo.mobile.feature.launch.RealLaunchPaneComponent
import app.tennyo.mobile.feature.terminal.RealTerminalPaneComponent
import app.tennyo.mobile.feature.terminal.TerminalPreferences
import app.tennyo.mobile.link.ActiveSurface
import app.tennyo.mobile.link.LinkAttachment
import app.tennyo.mobile.link.LinkSession
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.LiveSync
import app.tennyo.mobile.link.pane.WorkspacePane
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.children.ChildNavState
import com.arkivanov.decompose.router.pages.ChildPages
import com.arkivanov.decompose.router.pages.Pages
import com.arkivanov.decompose.router.pages.PagesNavigation
import com.arkivanov.decompose.router.pages.childPages
import com.arkivanov.decompose.router.pages.select
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The workspace over one [LinkSession]. The session owns the connection; this component projects its state into the
 * chrome, hands host events to the panes, runs fallback sync while live events may be missing, and tells the host
 * which pane and item are on screen.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class RealWorkspaceComponent(
    componentContext: ComponentContext,
    private val session: LinkSession,
    private val terminalPreferences: TerminalPreferences,
    sessionTicket: LinkTicket,
    private val onBackNav: () -> Unit,
    private val onTicketUpdated: (LinkTicket) -> Unit,
    private val onSessionEnded: () -> Unit,
    private val mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : WorkspaceComponent, ComponentContext by componentContext {
    private val store = FeatureStore<WorkspaceState, WorkspaceMsg, Nothing>(
        WorkspaceState(sessionTicket = sessionTicket),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private val scope get() = store.scope
    private val paneNavigation = PagesNavigation<WorkspacePaneKind>()
    private val bindings = mutableMapOf<WorkspacePaneKind, WorkspacePane>()
    private var files: RealFilesPaneComponent? = null
    private var terminal: RealTerminalPaneComponent? = null
    private var link: LinkState = session.state.value

    override val state: StateFlow<WorkspaceState> = store.state

    override val panes: Value<ChildPages<WorkspacePaneKind, WorkspacePaneChild>> = childPages(
        source = paneNavigation,
        serializer = null,
        initialPages = { Pages(WorkspacePaneKind.entries, state.value.selectedPane.ordinal) },
        // Every pane stays alive while the workspace is: switching panes keeps tabs, drafts and scroll.
        pageStatus = { index, pages ->
            if (index == pages.selectedIndex) ChildNavState.Status.RESUMED else ChildNavState.Status.CREATED
        },
        childFactory = ::createPane,
    )

    private val allPanes: List<WorkspacePane>
        get() = WorkspacePaneKind.entries.mapNotNull(bindings::get)

    private val attachment = object : LinkAttachment {
        override suspend fun loadSnapshot(capabilities: Set<Capability>) {
            val selected = state.value.selectedPane
            store.dispatch(WorkspaceMsg.SnapshotLoaded)
            WorkspacePaneKind.entries.forEach { kind -> bindings[kind]?.loadSnapshot(shown = kind == selected) }
        }

        override fun suspendSync(forBackground: Boolean) {
            allPanes.forEach { it.suspendSync(forBackground) }
        }
    }

    init {
        lifecycle.doOnCreate {
            observeSession()
            session.attach(attachment)
        }
        lifecycle.doOnResume { session.onForeground() }
        lifecycle.doOnStop { session.onBackground() }
        lifecycle.doOnDestroy { session.detach() }
    }

    override fun onIntent(intent: WorkspaceIntent) {
        when (intent) {
            WorkspaceIntent.RetryConnection -> session.retry()
            WorkspaceIntent.EndSession -> session.end()
            WorkspaceIntent.RefreshSessionStats -> session.refreshStats()
            is WorkspaceIntent.StatsVisibilityChanged -> session.setStatsVisible(intent.open)
            WorkspaceIntent.DismissConnectionMessage -> store.dispatch(WorkspaceMsg.ConnectionMessageDismissed)
            WorkspaceIntent.DismissActionFailure -> store.dispatch(WorkspaceMsg.ActionFailureDismissed)
            is WorkspaceIntent.TogglePairingWord -> session.togglePairingWord(intent.word)
            WorkspaceIntent.ClearPairingWords -> session.clearPairingWords()
            WorkspaceIntent.ConfirmPairing -> session.confirmPairing()
            is WorkspaceIntent.SelectPane -> selectPane(intent.pane)
            is WorkspaceIntent.SelectDestination -> selectDestination(intent.destination)
            is WorkspaceIntent.OpenPanel -> openPanel(intent.panel)
            WorkspaceIntent.ClosePanel -> closePanel()
        }
    }

    override fun onBack() {
        if (bindings[state.value.selectedPane]?.onBack() == true) return
        if (state.value.openPanel != null && closePanel()) return
        onBackNav()
    }

    private fun createPane(kind: WorkspacePaneKind, context: ComponentContext): WorkspacePaneChild {
        val isShown = { state.value.selectedPane == kind }
        return when (kind) {
            WorkspacePaneKind.CHAT -> RealChatPaneComponent(context, session, mainContext)
                .also { bindings[kind] = it }
                .let(WorkspacePaneChild::Chat)

            WorkspacePaneKind.FILES -> RealFilesPaneComponent(context, session, isShown, ::showSurface, mainContext)
                .also { files = it; bindings[kind] = it; observeNotices(it) }
                .let(WorkspacePaneChild::Files)

            WorkspacePaneKind.LAUNCH -> RealLaunchPaneComponent(context, session, mainContext)
                .also { bindings[kind] = it; observeNotices(it) }
                .let(WorkspacePaneChild::Launch)

            WorkspacePaneKind.TERMINAL ->
                RealTerminalPaneComponent(context, session, terminalPreferences, isShown, ::showSurface, mainContext)
                    .also { terminal = it; bindings[kind] = it; observeNotices(it) }
                    .let(WorkspacePaneChild::Terminal)

            WorkspacePaneKind.GIT -> RealGitPaneComponent(
                context,
                IdeGitSource(session),
                mainContext,
                openInEditor = ::openInEditor,
            )
                .also { git ->
                    bindings[kind] = LinkGitPane(git, session, isShown, context.lifecycle, mainContext)
                    observeNotices(git)
                }
                .let(WorkspacePaneChild::Git)
        }
    }

    private fun observeSession() {
        session.state.onEach(::onLinkChanged).launchIn(scope)
        session.project.onEach { project ->
            val before = state.value.selectedPane
            store.dispatch(WorkspaceMsg.ProjectChanged(project))
            val after = state.value.selectedPane
            if (after != before) paneNavigation.select(after.ordinal)
        }.launchIn(scope)
        session.stats.onEach { store.dispatch(WorkspaceMsg.StatsChanged(it)) }.launchIn(scope)
        session.pushes.onEach { push -> if (allPanes.applyAll(push)) session.acknowledge(push) }.launchIn(scope)
    }

    private fun onLinkChanged(next: LinkState) {
        val previous = link
        link = next
        store.dispatch(WorkspaceMsg.LinkChanged(next, session.ticket.value))
        if (next is LinkState.Live && previous !is LinkState.Live) {
            session.ticket.value?.let(onTicketUpdated)
        }
        if (next is LinkState.Live && next.sync == LiveSync.Ready) showSurface(force = true)
        if (next is LinkState.Ended && previous !is LinkState.Ended) {
            onSessionEnded()
        }
    }

    private fun observeNotices(pane: Any) {
        val notices = when (pane) {
            is RealFilesPaneComponent -> pane.notices
            is RealLaunchPaneComponent -> pane.notices
            is RealTerminalPaneComponent -> pane.notices
            is RealGitPaneComponent -> pane.notices
            else -> return
        }
        notices.onEach(::onNotice).launchIn(scope)
    }

    private fun onNotice(notice: PaneNotice) {
        when (notice) {
            is PaneNotice.Failure -> store.dispatch(WorkspaceMsg.ActionFailed(notice.text))
            PaneNotice.Recovered -> store.dispatch(WorkspaceMsg.ActionRecovered)
        }
    }

    /** Shows the Files panel with [path] open in the editor, there and on the computer. */
    private fun openInEditor(path: String) {
        val files = files ?: return
        openPanel(WorkspacePanel.FILES)
        files.onIntent(FilesIntent.OpenFile(path))
    }

    /** Moving to a destination closes whatever panel was over the work. */
    private fun selectDestination(destination: WorkspaceDestination) {
        store.dispatch(WorkspaceMsg.PanelClosed)
        selectPane(destination.pane)
    }

    private fun openPanel(panel: WorkspacePanel) {
        store.dispatch(WorkspaceMsg.PanelOpened(panel))
        selectPane(panel.pane)
    }

    /** Closing a panel returns to the destination the user was last in; destinations are always there. */
    private fun closePanel(): Boolean {
        store.dispatch(WorkspaceMsg.PanelClosed)
        selectPane(state.value.lastDestination.pane)
        return true
    }

    private fun selectPane(pane: WorkspacePaneKind) {
        Logger.d { "Pane selected from=${state.value.selectedPane} to=$pane" }
        val snapshotDue = pane in state.value.snapshotDue
        store.dispatch(WorkspaceMsg.PaneSelected(pane))
        paneNavigation.select(pane.ordinal)
        showSurface()
        val binding = bindings[pane] ?: return
        if (snapshotDue) scope.launch { binding.loadSnapshot(shown = true) } else binding.onShown()
    }

    /** Tells the host which pane, file, and terminal are on screen. */
    private fun showSurface(force: Boolean = false) {
        session.showSurface(
            ActiveSurface(
                panel = state.value.selectedPane.name,
                selectedFilePath = files?.state?.value?.selectedFile?.path,
                selectedTerminalId = terminal?.state?.value?.selectedTerminal?.id,
            ),
            force,
        )
    }
}
