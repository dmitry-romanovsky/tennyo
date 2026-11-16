package app.tennyo.mobile.feature.local

import app.tennyo.mobile.design.AutosaveCountdown
import app.tennyo.mobile.design.UiText

/** The two panes of a local workspace. */
enum class LocalPane { Files, Git }

/** One open file: what the editor shows, what is on disk, and its undo and redo history. */
data class LocalEditorState(
    val text: String = "",
    val savedText: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val fault: WorkspaceFault? = null,
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
    /** The autosave waiting to run, drawn on the save button. */
    val autosave: AutosaveCountdown? = null,
) {
    val isDirty: Boolean get() = text != savedText
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

/** The directory the file navigator shows; [directory] is relative to the workspace root, `""` for the root. */
data class LocalNavigatorState(
    val directory: String = "",
    val entries: List<WorkspaceEntry> = emptyList(),
    val loading: Boolean = true,
    val fault: WorkspaceFault? = null,
)

data class LocalWorkspaceState(
    val slug: String,
    val workspace: LocalWorkspace? = null,
    val selectedPane: LocalPane = LocalPane.Files,
    /** The file drawer is open over the editor; back closes it first. */
    val navigatorOpen: Boolean = false,
    val navigator: LocalNavigatorState = LocalNavigatorState(),
    val openedFiles: List<String> = emptyList(),
    val selectedTab: Int = 0,
    val editors: Map<String, LocalEditorState> = emptyMap(),
    /** Whether this device can run Python at all; without a runner there is no Run button. */
    val canRunPython: Boolean = false,
    /** The last run, shown until dismissed. */
    val run: LocalRunState? = null,
    /** The packages being installed for this workspace, with the consent each step waits for. */
    val packages: PackageInstall? = null,
    /** The line of `requirements.txt` that was not a plain requirement, so the file was not offered for install. */
    val requirementsRejectedLine: Int? = null,
    /** What the user declined to install from `requirements.txt`; kept only while the workspace is open. */
    val declinedRequirements: List<String>? = null,
    /** An action that failed, shown in a banner until dismissed. */
    val failure: UiText? = null,
) {
    val safeSelectedTab: Int get() = if (openedFiles.isEmpty()) 0 else selectedTab.coerceIn(0, openedFiles.lastIndex)
    val selectedFile: String? get() = openedFiles.getOrNull(safeSelectedTab)
    val selectedEditor: LocalEditorState? get() = selectedFile?.let(editors::get)

    /** Whether Run does anything now: not while a run or an install is under way, or waiting on a dialog. */
    val canRun: Boolean get() = run?.running != true && packages == null
}

/** Every change to [LocalWorkspaceState]; applied only through [reduce]. */
internal sealed interface LocalWorkspaceMsg {
    data class WorkspaceLoaded(val workspace: LocalWorkspace) : LocalWorkspaceMsg
    data class PaneSelected(val pane: LocalPane) : LocalWorkspaceMsg
    data class NavigatorOpenChanged(val open: Boolean) : LocalWorkspaceMsg
    data class DirectoryRequested(val directory: String) : LocalWorkspaceMsg
    data class DirectoryListed(val directory: String, val entries: List<WorkspaceEntry>) : LocalWorkspaceMsg
    data class DirectoryFailed(val directory: String, val fault: WorkspaceFault) : LocalWorkspaceMsg
    data class TabsRestored(val paths: List<String>, val selectedTab: Int) : LocalWorkspaceMsg
    data class FileOpened(val path: String) : LocalWorkspaceMsg
    data class FileClosed(val path: String) : LocalWorkspaceMsg
    data class TabSelected(val index: Int) : LocalWorkspaceMsg
    data class EditorLoaded(val path: String, val text: String) : LocalWorkspaceMsg
    data class EditorFailed(val path: String, val fault: WorkspaceFault) : LocalWorkspaceMsg
    data class TextChanged(val path: String, val text: String) : LocalWorkspaceMsg
    data class Undone(val path: String) : LocalWorkspaceMsg
    data class Redone(val path: String) : LocalWorkspaceMsg
    data class SaveStarted(val path: String) : LocalWorkspaceMsg
    data class AutosaveScheduled(val path: String, val countdown: AutosaveCountdown) : LocalWorkspaceMsg
    data class Saved(val path: String, val text: String) : LocalWorkspaceMsg
    data class SaveFailed(val path: String, val fault: WorkspaceFault) : LocalWorkspaceMsg
    data class RunStarted(val path: String) : LocalWorkspaceMsg
    data class RunFinished(val path: String, val result: PythonRun) : LocalWorkspaceMsg
    data object RunDismissed : LocalWorkspaceMsg
    data class Failed(val text: UiText) : LocalWorkspaceMsg
    data object FailureDismissed : LocalWorkspaceMsg
}

internal fun reduce(state: LocalWorkspaceState, msg: LocalWorkspaceMsg): LocalWorkspaceState = when (msg) {
    is LocalWorkspaceMsg.WorkspaceLoaded -> state.copy(workspace = msg.workspace)
    is LocalWorkspaceMsg.PaneSelected -> state.copy(selectedPane = msg.pane, navigatorOpen = false)
    is LocalWorkspaceMsg.NavigatorOpenChanged -> state.copy(navigatorOpen = msg.open)
    is LocalWorkspaceMsg.DirectoryRequested ->
        state.copy(navigator = state.navigator.copy(directory = msg.directory, loading = true, fault = null))

    is LocalWorkspaceMsg.DirectoryListed -> state.withNavigator(msg.directory) {
        copy(entries = msg.entries, loading = false, fault = null)
    }

    is LocalWorkspaceMsg.DirectoryFailed ->
        state.withNavigator(msg.directory) { copy(loading = false, fault = msg.fault) }
    is LocalWorkspaceMsg.TabsRestored -> state.copy(
        openedFiles = msg.paths.distinct(),
        selectedTab = msg.selectedTab,
        editors = msg.paths.associateWith { state.editors[it] ?: LocalEditorState() },
    )

    is LocalWorkspaceMsg.RunStarted -> state.copy(run = LocalRunState(msg.path))
    // A result for a run the user already dismissed, or replaced, is dropped.
    is LocalWorkspaceMsg.RunFinished ->
        if (state.run?.path == msg.path) {
            state.copy(run = LocalRunState(msg.path, running = false, msg.result))
        } else {
            state
        }

    LocalWorkspaceMsg.RunDismissed -> state.copy(run = null)
    is LocalWorkspaceMsg.Failed -> state.copy(failure = msg.text)
    LocalWorkspaceMsg.FailureDismissed -> state.copy(failure = null)
    is LocalWorkspaceMsg.FileOpened -> state.openFile(msg.path)
    is LocalWorkspaceMsg.FileClosed -> state.closeFile(msg.path)
    is LocalWorkspaceMsg.TabSelected -> state.copy(
        selectedTab = if (state.openedFiles.isEmpty()) 0 else msg.index.coerceIn(0, state.openedFiles.lastIndex),
    )

    is LocalPackagesMsg -> state.reducePackages(msg)
    else -> state.reduceEditor(msg)
}

private fun LocalWorkspaceState.reduceEditor(msg: LocalWorkspaceMsg): LocalWorkspaceState = when (msg) {
    is LocalWorkspaceMsg.EditorLoaded -> withEditor(msg.path) { loaded(msg.text) }
    is LocalWorkspaceMsg.EditorFailed -> withEditor(msg.path) { copy(loading = false, fault = msg.fault) }
    is LocalWorkspaceMsg.TextChanged -> withEditor(msg.path) { edited(msg.text) ?: this }
    is LocalWorkspaceMsg.Undone -> withEditor(msg.path) { undone() ?: this }
    is LocalWorkspaceMsg.Redone -> withEditor(msg.path) { redone() ?: this }
    is LocalWorkspaceMsg.SaveStarted -> withEditor(msg.path) { copy(saving = true, fault = null, autosave = null) }
    is LocalWorkspaceMsg.AutosaveScheduled -> withEditor(msg.path) { copy(autosave = msg.countdown) }
    is LocalWorkspaceMsg.Saved -> withEditor(msg.path) { copy(savedText = msg.text, saving = false, fault = null) }
    is LocalWorkspaceMsg.SaveFailed -> withEditor(msg.path) { copy(saving = false, fault = msg.fault) }
    else -> this
}

/** The freshly read [diskText] replaces what the editor shows; the history is kept. */
internal fun LocalEditorState.loaded(diskText: String): LocalEditorState =
    copy(text = diskText, savedText = diskText, loading = false, saving = false, fault = null)

/** Typing [newText]: the previous text becomes undoable and the redo history ends. `null` when nothing changes. */
internal fun LocalEditorState.edited(newText: String): LocalEditorState? {
    if (loading || text == newText) return null
    return copy(text = newText, undoStack = undoStack + text, redoStack = emptyList(), fault = null)
}

internal fun LocalEditorState.undone(): LocalEditorState? {
    val previous = undoStack.lastOrNull() ?: return null
    return copy(text = previous, undoStack = undoStack.dropLast(1), redoStack = redoStack + text, fault = null)
}

internal fun LocalEditorState.redone(): LocalEditorState? {
    val next = redoStack.lastOrNull() ?: return null
    return copy(text = next, undoStack = undoStack + text, redoStack = redoStack.dropLast(1), fault = null)
}

private fun LocalWorkspaceState.openFile(path: String): LocalWorkspaceState {
    val index = openedFiles.indexOf(path)
    if (index != -1) return copy(selectedTab = index)
    return copy(
        openedFiles = openedFiles + path,
        selectedTab = openedFiles.size,
        editors = editors + (path to (editors[path] ?: LocalEditorState())),
    )
}

private fun LocalWorkspaceState.closeFile(path: String): LocalWorkspaceState {
    val index = openedFiles.indexOf(path)
    if (index == -1) return this
    val remaining = openedFiles - path
    val selected = safeSelectedTab
    val nextSelected = when {
        remaining.isEmpty() -> 0
        selected > index -> selected - 1
        else -> selected.coerceAtMost(remaining.lastIndex)
    }
    return copy(openedFiles = remaining, selectedTab = nextSelected, editors = editors - path)
}

private fun LocalWorkspaceState.withEditor(path: String, transform: LocalEditorState.() -> LocalEditorState) =
    editors[path]?.let { copy(editors = editors + (path to it.transform())) } ?: this

/** A listing that arrives after the user moved to another directory is dropped. */
private fun LocalWorkspaceState.withNavigator(
    directory: String,
    transform: LocalNavigatorState.() -> LocalNavigatorState,
) = if (navigator.directory == directory) copy(navigator = navigator.transform()) else this
