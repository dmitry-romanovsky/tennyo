package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkPush
import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalSnapshotResponse
import app.tennyo.contract.TerminalTabsState
import app.tennyo.mobile.design.PaneNotice
import app.tennyo.mobile.design.failureText
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.pane.PaneLink
import app.tennyo.mobile.link.pane.PaneRequests
import app.tennyo.mobile.link.pane.SyncPass
import app.tennyo.mobile.link.pane.WorkspacePane
import app.tennyo.mobile.link.pane.nowEpochMillis
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The terminal pane over [link]. Input, select and open go over the event stream when it is live and fall back
 * to HTTP otherwise; the host streams frames only for the terminal on screen.
 */
@Suppress("TooManyFunctions")
class RealTerminalPaneComponent(
    componentContext: ComponentContext,
    private val link: PaneLink,
    private val preferences: TerminalPreferences,
    private val isShown: () -> Boolean,
    private val onSurfaceChanged: (force: Boolean) -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : TerminalPaneComponent, WorkspacePane, ComponentContext by componentContext {
    private val store = FeatureStore<TerminalState, TerminalMsg, PaneNotice>(
        TerminalState(),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private val client = TerminalClient(link)
    private val requests = PaneRequests<TerminalRequest>()
    private val scope get() = store.scope
    private val inputDispatcher = TerminalInputDispatcher(
        scope = scope,
        sendInput = ::sendInput,
        onFailure = { notifyFailure(TerminalAction.SendTerminalInput, it, sticky = true) },
    )

    override val state: StateFlow<TerminalState> = store.state
    override val keyBar: StateFlow<TerminalKeyBarSettings> = preferences.keyBar
    override val notices: Flow<PaneNotice> = store.effects

    private val current: TerminalState get() = store.state.value
    private val connected: Boolean get() = link.ticket.value != null

    /** The terminal input and refreshes go to: the selected one, and only while the pane is on screen. */
    private val visibleTerminal get() = current.selectedTerminal?.takeIf { isShown() }

    override fun onIntent(intent: TerminalIntent) {
        when (intent) {
            TerminalIntent.Create -> if (connected) createTerminal()
            is TerminalIntent.Select -> if (connected) selectTerminal(intent.id)
            is TerminalIntent.Close -> if (connected) closeTerminal(intent.id)
            is TerminalIntent.SendInput -> visibleTerminal?.let { inputDispatcher.send(it.id, intent.text) }
            is TerminalIntent.AddHotkey -> preferences.edit(KeyBarEdit.AddHotkey(intent.hotkey))
        }
    }

    // WorkspacePane ---------------------------------------------------------------------------

    override suspend fun loadSnapshot(shown: Boolean) {
        if (!shown || !connected || Capability.TERMINAL !in link.project.value.capabilities) return
        runCatching { client.getTerminalTabs() }
            .onSuccess(::applyTabsOrCreate)
            .onFailure { notifyFailure(TerminalAction.LoadTerminalTabs, it) }
        refreshSelectedSnapshot(explicit = true)
    }

    override suspend fun sync(pass: SyncPass) {
        if (!pass.shown) return
        val due = requests.allowed(
            current.syncRequests(pass.nowEpochMillis, pass.metadata, pass.explicitContent),
            pass.nowEpochMillis,
        )
        if (TerminalRequest.Tabs in due) refreshTabs()
        if (TerminalRequest.SelectedSnapshot in due) refreshSelectedSnapshot(explicit = pass.explicitContent)
    }

    override fun apply(push: LinkPush) {
        when (push) {
            is LinkPush.TerminalTabs -> applyTabs(push.state)
            is LinkPush.TerminalOutput -> applyFrame(push.frame)
            else -> Unit
        }
    }

    /** The IDE may have no terminal open yet; showing the pane opens one, then refreshes what it shows. */
    override fun onShown() {
        if (!connected) return
        ensureTerminalOpen()
        scope.launch { sync(SyncPass.userRefresh()) }
    }

    // Terminals --------------------------------------------------------------------------------

    private fun ensureTerminalOpen() {
        if (Capability.TERMINAL !in link.project.value.capabilities) return
        scope.launch {
            runCatching {
                if (client.ensureTerminalOpen()) {
                    refreshTabs()
                } else {
                    Logger.e { "The IDE did not open its terminal window" }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Logger.e(throwable) { "Unable to open optional IDE terminal from mobile terminal surface" }
            }
        }
    }

    private fun createTerminal() {
        scope.launch {
            runCatching { client.createTerminal() }
                .onSuccess { tab ->
                    store.dispatch(TerminalMsg.Created(tab, nowEpochMillis()))
                    onSurfaceChanged(false)
                    refreshSelectedSnapshot(explicit = true)
                }
                .onFailure { notifyFailure(TerminalAction.CreateTerminal, it, sticky = true) }
        }
    }

    private fun selectTerminal(id: String) {
        store.dispatch(TerminalMsg.Selected(id))
        inputDispatcher.clear()
        scope.launch {
            runCatching { client.selectTerminal(id) }
                .onSuccess { selected ->
                    if (selected) onSurfaceChanged(true) else store.dispatch(TerminalMsg.PendingSelectionCleared(id))
                }
                .onFailure { throwable ->
                    store.dispatch(TerminalMsg.PendingSelectionCleared(id))
                    notifyFailure(TerminalAction.SelectTerminal, throwable, sticky = true)
                }
            refreshSelectedSnapshot(explicit = true)
        }
    }

    private fun closeTerminal(id: String) {
        if (current.selectedTerminalId == id) inputDispatcher.clear()
        store.dispatch(TerminalMsg.Closed(id))
        onSurfaceChanged(false)
        scope.launch {
            runCatching { client.closeTerminal(id) }
                .onSuccess {
                    runCatching { client.getTerminalTabs() }
                        .onSuccess(::applyTabs)
                        .onFailure { notifyFailure(TerminalAction.RefreshTerminalTabs, it, sticky = true) }
                }
                .onFailure { notifyFailure(TerminalAction.CloseTerminal, it, sticky = true) }
        }
    }

    /** Typed input; the IDE answers refusals as terminal output rather than as a failed call. */
    private suspend fun sendInput(terminalId: String, text: String) {
        if (!connected) return
        client.sendTerminalInput(terminalId, text)
    }

    private fun closeMissingTerminal(terminalId: String, throwable: Throwable) {
        Logger.w("Selected terminal is gone in the IDE; closing its tab target=$terminalId", throwable)
        if (current.selectedTerminalId == terminalId) inputDispatcher.clear()
        store.dispatch(TerminalMsg.Missing(terminalId))
        store.emit(PaneNotice.Recovered)
        onSurfaceChanged(false)
        if (connected && isShown()) scope.launch { sync(SyncPass.userRefresh()) }
    }

    // Tabs, frames and snapshots ---------------------------------------------------------------

    /** The IDE always keeps a terminal for the phone: an empty tab list means one gets created. */
    private fun applyTabsOrCreate(tabs: TerminalTabsState) {
        if (tabs.tabs.isEmpty()) createTerminal() else applyTabs(tabs)
    }

    private fun applyTabs(tabs: TerminalTabsState) {
        store.dispatch(TerminalMsg.TabsReceived(tabs))
        onSurfaceChanged(false)
        if (connected && isShown()) {
            scope.launch {
                if (requests.allowed(setOf(TerminalRequest.SelectedSnapshot), nowEpochMillis()).isNotEmpty()) {
                    refreshSelectedSnapshot(explicit = false)
                }
            }
        }
    }

    private fun applyFrame(frame: TerminalFrame) {
        if (!current.shouldApplyFrame(frame.terminalId, isShown())) {
            store.dispatch(TerminalMsg.SnapshotRefreshCleared(frame.terminalId))
            return
        }
        store.dispatch(TerminalMsg.FrameReceived(frame, nowEpochMillis()))
        // A frame that referenced styles the phone lacks waits for a snapshot carrying all of them.
        if (frame.terminalId in current.styleRecoveryTerminalIds) {
            store.dispatch(TerminalMsg.SnapshotRefreshCleared(frame.terminalId))
            if (connected) scope.launch { refreshSelectedSnapshot(explicit = true) }
        }
    }

    private fun applySnapshot(response: TerminalSnapshotResponse) {
        if (!isShown() || current.selectedTerminal?.id != response.terminalId) {
            store.dispatch(TerminalMsg.SnapshotRefreshCleared(response.terminalId))
            return
        }
        applyFrame(
            TerminalFrame(
                terminalId = response.terminalId,
                text = "",
                snapshot = response.snapshot,
                revision = response.revision,
            ),
        )
        // An HTTP snapshot is a complete answer, including an intentionally empty terminal: the pane must not wait
        // in its loading state for a later event-stream frame.
        store.dispatch(TerminalMsg.SnapshotLoaded(response.terminalId, nowEpochMillis()))
    }

    private suspend fun refreshTabs() {
        requests.run(TerminalRequest.Tabs, "Unable to refresh terminal tabs from fallback sync") {
            client.getTerminalTabs()
        }?.let(::applyTabsOrCreate)
    }

    private suspend fun refreshSelectedSnapshot(explicit: Boolean) {
        val terminal = visibleTerminal ?: return
        if (!current.snapshotCanStartRefresh(terminal.id)) return
        if (!explicit && !current.snapshotNeedsRefresh(terminal.id, nowEpochMillis())) return
        val includeAllStyles = terminal.id in current.styleRecoveryTerminalIds || current.styleEpoch == 0L
        store.dispatch(TerminalMsg.SnapshotRefreshStarted(terminal.id))
        runCatching { client.getTerminalSnapshot(terminal.id, includeAllStyles = includeAllStyles) }
            .onSuccess { response ->
                requests.succeeded(TerminalRequest.SelectedSnapshot)
                applySnapshot(response)
            }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (throwable is LinkFault.TargetMissing) {
                    closeMissingTerminal(terminal.id, throwable)
                } else {
                    store.dispatch(TerminalMsg.SnapshotRefreshFailed(terminal.id))
                    requests.failed(
                        TerminalRequest.SelectedSnapshot,
                        throwable,
                        "Unable to refresh terminal snapshot from fallback sync",
                    )
                }
            }
    }

    private fun notifyFailure(action: TerminalAction, throwable: Throwable, sticky: Boolean = false) {
        if (throwable is CancellationException) throw throwable
        Logger.w("Terminal action $action failed", throwable)
        store.emit(PaneNotice.Failure(failureText(action.failure, throwable.message), sticky))
    }
}
