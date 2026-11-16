package app.tennyo.mobile.feature.files

import app.tennyo.contract.Capability
import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.EditorTabsState
import app.tennyo.contract.FileContent
import app.tennyo.contract.LinkPush
import app.tennyo.contract.WriteFileRequest
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * The files pane over [link]. Edits autosave after a short pause; the IDE's editor tabs are mirrored both ways, with
 * the phone's own just-made selection winning until the IDE confirms it.
 */
class RealFilesPaneComponent(
    componentContext: ComponentContext,
    private val link: PaneLink,
    private val isShown: () -> Boolean,
    private val onSurfaceChanged: () -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : FilesPaneComponent, WorkspacePane, ComponentContext by componentContext {
    private val store = FeatureStore<FilesState, FilesMsg, PaneNotice>(FilesState(), lifecycle, ::reduce, mainContext)
    private val client = FilesClient(link)
    private val requests = PaneRequests<FilesRequest>()
    private val autosaveJobs = mutableMapOf<String, Job>()
    private var launchCandidatesJob: Job? = null
    private val scope get() = store.scope

    override val state: StateFlow<FilesState> = store.state
    override val notices: Flow<PaneNotice> = store.effects

    private val current: FilesState get() = store.state.value
    private val available: Boolean
        get() = link.ticket.value != null && Capability.FILES in link.project.value.capabilities

    override fun onIntent(intent: FilesIntent) {
        when (intent) {
            is FilesIntent.LoadFolder -> loadFolder(intent.path)
            is FilesIntent.OpenFile -> openFile(intent.path)
            is FilesIntent.SelectTab -> selectTab(intent.index)
            is FilesIntent.CloseTab -> closeTab(intent.path)
            FilesIntent.RefreshSelectedFile -> if (link.ticket.value != null) {
                scope.launch { refreshSelectedContent(explicit = true) }
            }

            is FilesIntent.EditText -> editText(intent.path, intent.text)
            FilesIntent.SaveSelectedFile -> current.selectedFile?.let { saveFile(it.path) }
            is FilesIntent.LaunchConfiguration -> launchConfiguration(intent.id)
            is FilesIntent.StopLaunchConfiguration -> stopLaunchConfiguration(intent.id)
            FilesIntent.Undo -> stepHistory(back = true)
            FilesIntent.Redo -> stepHistory(back = false)
        }
    }

    // WorkspacePane ---------------------------------------------------------------------------

    override suspend fun loadSnapshot(shown: Boolean) {
        if (!shown || !available) return
        runCatching { client.getFileTree() }
            .onSuccess { store.dispatch(FilesMsg.TreeLoaded(it.root)) }
            .onFailure { notifyFailure(FilesAction.LoadFiles, it) }
        runCatching { client.getEditorTabs() }
            .onSuccess(::applyEditorTabs)
            .onFailure { notifyFailure(FilesAction.LoadEditorTabs, it) }
        refreshSelectedContent(explicit = true)
    }

    override suspend fun sync(pass: SyncPass) {
        if (!pass.shown) return
        val due = requests.allowed(
            current.syncRequests(pass.nowEpochMillis, pass.metadata, pass.explicitContent),
            pass.nowEpochMillis,
        )
        if (FilesRequest.EditorTabs in due) {
            requests.run(FilesRequest.EditorTabs, "Unable to refresh IDE editor tabs from fallback sync") {
                client.getEditorTabs()
            }?.let(::applyEditorTabs)
        }
        if (FilesRequest.SelectedFileContent in due) refreshSelectedContent(explicit = pass.explicitContent)
    }

    override fun apply(push: LinkPush) {
        when (push) {
            is LinkPush.EditorTabs -> applyEditorTabs(push.state)
            is LinkPush.FileContentChanged -> applyFileContent(push.content)
            else -> Unit
        }
    }

    override fun suspendSync(forBackground: Boolean) {
        if (forBackground) {
            autosaveJobs.values.forEach { it.cancel() }
            autosaveJobs.clear()
        }
    }

    override fun onShown() {
        refreshSoon()
    }

    // Tree and tabs ----------------------------------------------------------------------------

    private fun loadFolder(path: String) {
        if (!available) return
        scope.launch {
            runCatching { client.getFileTree(path) }
                .onSuccess { store.dispatch(FilesMsg.FolderLoaded(it.root)) }
                .onFailure { notifyFailure(FilesAction.LoadFolder, it) }
        }
    }

    private fun openFile(path: String) {
        if (!available) return
        store.dispatch(FilesMsg.OpenRequested(path))
        store.emit(PaneNotice.Recovered)
        onSurfaceChanged()
        lookupLaunchCandidates(path)
        scope.launch {
            runCatching {
                if (EditorTabAction.OPEN in current.allowedTabActions) client.openEditorTab(path)
                client.readFile(path)
            }
                .onSuccess { content ->
                    store.dispatch(FilesMsg.Opened(content, preferredSelectedPath = null, loadedAt = nowEpochMillis()))
                    store.emit(PaneNotice.Recovered)
                    onSurfaceChanged()
                }
                .onFailure { throwable ->
                    if (throwable is LinkFault.TargetMissing) {
                        closeMissingFile(path, throwable)
                    } else {
                        store.dispatch(FilesMsg.ContentRefreshFailed(path))
                        notifyFailure(FilesAction.OpenFile, throwable, sticky = true)
                    }
                }
        }
    }

    private fun selectTab(index: Int) {
        val connected = link.ticket.value != null
        val file = current.openedFiles.getOrNull(index)
        val remotelySelectable = connected && EditorTabAction.SELECT in current.allowedTabActions
        store.dispatch(FilesMsg.TabSelected(index, pendingPath = file?.path?.takeIf { remotelySelectable }))
        if (!remotelySelectable || file == null) return
        lookupLaunchCandidates(file.path)
        scope.launch {
            runCatching { client.selectEditorTab(file.path) }
                .onSuccess {
                    onSurfaceChanged()
                    refreshSoon()
                }
                .onFailure { throwable ->
                    store.dispatch(FilesMsg.PendingSelectionCleared(file.path))
                    notifyFailure(FilesAction.SelectEditorTab, throwable)
                }
        }
    }

    private fun closeTab(path: String) {
        autosaveJobs.remove(path)?.cancel()
        store.dispatch(FilesMsg.TabClosed(path))
        onSurfaceChanged()
        if (link.ticket.value == null || EditorTabAction.CLOSE !in current.allowedTabActions) return
        scope.launch {
            runCatching { client.closeEditorTab(path) }
                .onFailure { notifyFailure(FilesAction.CloseEditorTab, it) }
        }
    }

    private fun applyEditorTabs(tabs: EditorTabsState) {
        store.dispatch(FilesMsg.EditorTabsReceived(tabs))
        onSurfaceChanged()
        current.selectedFile?.path?.let(::lookupLaunchCandidates)
        refreshSelectedContentSoon()
    }

    private fun launchConfiguration(id: String) {
        if (current.launchCandidates.none { it.configurationId == id }) return
        scope.launch {
            runCatching { client.startLaunch(id) }
                .onFailure { notifyFailure(FilesAction.StartLaunch, it) }
        }
    }

    private fun stopLaunchConfiguration(id: String) {
        scope.launch {
            runCatching { client.stopLaunch(id) }
                .onFailure { notifyFailure(FilesAction.StopLaunch, it) }
        }
    }

    /** IDE content for a file counts only while that file is on screen; other tabs refetch when selected. */
    private fun applyFileContent(content: FileContent) {
        if (!isShown() || current.selectedFile?.path != content.path) {
            store.dispatch(FilesMsg.ContentRefreshCleared(content.path))
            return
        }
        store.dispatch(FilesMsg.ContentReceived(content, nowEpochMillis()))
    }

    private fun closeMissingFile(path: String, throwable: Throwable) {
        Logger.w("Selected file is gone in the IDE; closing its tab target=$path", throwable)
        autosaveJobs.remove(path)?.cancel()
        store.dispatch(FilesMsg.FileMissing(path))
        store.emit(PaneNotice.Recovered)
        onSurfaceChanged()
        refreshSoon()
    }

    // Editing and saving -----------------------------------------------------------------------

    private fun editText(path: String, text: String) {
        val file = current.file(path) ?: return
        if (!file.viewable || file.text == text) return
        store.dispatch(FilesMsg.TextEdited(path, text))
        scheduleAutosave(path)
    }

    private fun stepHistory(back: Boolean) {
        val file = current.selectedFile?.takeIf { it.viewable } ?: return
        val canStep = if (back) current.canUndo(file.path) else current.canRedo(file.path)
        if (!canStep) return
        store.dispatch(if (back) FilesMsg.Undone(file.path) else FilesMsg.Redone(file.path))
        scheduleAutosave(file.path)
    }

    private fun scheduleAutosave(path: String) {
        autosaveJobs.remove(path)?.cancel()
        val file = current.file(path) ?: return
        if (!file.isDirty || !file.viewable || !file.writable) {
            store.dispatch(FilesMsg.AutosaveDropped(path))
            return
        }
        store.dispatch(FilesMsg.AutosaveScheduled(path, nowEpochMillis(), AUTOSAVE_DELAY_MILLIS))
        autosaveJobs[path] = scope.launch {
            delay(AUTOSAVE_DELAY_MILLIS.milliseconds)
            saveFile(path)
        }
    }

    private fun saveFile(path: String) {
        if (link.ticket.value == null) return
        val file = current.file(path) ?: return
        if (!file.viewable || !file.writable || file.syncState == FileSyncState.SAVING) return
        autosaveJobs.remove(path)?.cancel()
        if (file.text == file.savedText) {
            store.dispatch(FilesMsg.SyncStateChanged(path, FileSyncState.SYNCED))
            return
        }
        scope.launch {
            store.dispatch(FilesMsg.SyncStateChanged(path, FileSyncState.SAVING))
            store.emit(PaneNotice.Recovered)
            runCatching {
                client.writeFile(WriteFileRequest(path = file.path, text = file.text, expectedRevision = file.revision))
            }.onSuccess { saved ->
                store.dispatch(FilesMsg.Saved(sentText = file.text, saved = saved, loadedAt = nowEpochMillis()))
            }.onFailure {
                store.dispatch(FilesMsg.SyncStateChanged(path, FileSyncState.MODIFIED))
                notifyFailure(FilesAction.SaveFile, it, sticky = true)
            }
        }
    }

    // Background refresh -----------------------------------------------------------------------

    /** Refreshes what the pane shows, as right after the user changed it. */
    private fun refreshSoon() {
        if (link.ticket.value == null || !isShown()) return
        scope.launch { sync(SyncPass.userRefresh()) }
    }

    private fun refreshSelectedContentSoon() {
        if (link.ticket.value == null || !isShown()) return
        scope.launch {
            if (requests.allowed(setOf(FilesRequest.SelectedFileContent), nowEpochMillis()).isNotEmpty()) {
                refreshSelectedContent(explicit = false)
            }
        }
    }

    private suspend fun refreshSelectedContent(explicit: Boolean) {
        val path = current.selectedFile?.path ?: return
        if (!current.contentCanStartRefresh(path)) return
        if (!explicit && !current.contentNeedsRefresh(path, nowEpochMillis())) return
        if (current.file(path)?.syncState == FileSyncState.SAVING) return
        store.dispatch(FilesMsg.ContentRefreshStarted(path))
        runCatching { client.readFile(path) }
            .onSuccess {
                requests.succeeded(FilesRequest.SelectedFileContent)
                applyFileContent(it)
            }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (throwable is LinkFault.TargetMissing) {
                    closeMissingFile(path, throwable)
                } else {
                    store.dispatch(FilesMsg.ContentRefreshFailed(path))
                    requests.failed(
                        FilesRequest.SelectedFileContent,
                        throwable,
                        "Unable to refresh remote file $path from fallback sync",
                    )
                }
            }
    }

    /** Looks up runnable configurations for [path] without making the file open wait on the backend. */
    private fun lookupLaunchCandidates(path: String) {
        if (Capability.LAUNCH_CONFIGURATIONS !in link.project.value.capabilities) return
        launchCandidatesJob?.cancel()
        store.dispatch(FilesMsg.LaunchCandidatesReceived(path, emptyList()))
        launchCandidatesJob = scope.launch {
            runCatching { client.getLaunchCandidates(path) }
                .onSuccess { store.dispatch(FilesMsg.LaunchCandidatesReceived(path, it)) }
                .onFailure { Logger.d("Unable to find launch configurations for $path", it) }
        }
    }

    /** A [sticky] failure stays in the workspace banner until the pane recovers; others only show once. */
    private fun notifyFailure(action: FilesAction, throwable: Throwable, sticky: Boolean = false) {
        if (throwable is CancellationException) throw throwable
        Logger.w("Files action $action failed", throwable)
        store.emit(PaneNotice.Failure(failureText(action.failure, throwable.message), sticky))
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 3_000L
    }
}
