package app.tennyo.mobile.feature.files

import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.FileNode
import app.tennyo.contract.LaunchCandidate
import app.tennyo.mobile.design.AutosaveCountdown
import app.tennyo.mobile.link.pane.ContentFreshness
import app.tennyo.mobile.link.pane.ContentRefreshState
import app.tennyo.mobile.link.pane.ContentRequestKind
import app.tennyo.mobile.link.pane.canStartRefresh
import app.tennyo.mobile.link.pane.needsRefresh

/** The files pane: the project tree, the editor tabs opened from it, and each tab's edit history. */
data class FilesState(
    val root: FileNode? = null,
    val openedFiles: List<OpenedFile> = emptyList(),
    val contentFreshness: Map<String, ContentFreshness> = emptyMap(),
    val selectedTabIndex: Int = 0,
    /** A tab selected on the phone that the IDE has not confirmed yet; its tab list must not undo the selection. */
    val pendingEditorSelectionPath: String? = null,
    val history: Map<String, EditHistory> = emptyMap(),
    /** The tab actions this host accepts; the pane renders and sends only these. */
    val allowedTabActions: Set<EditorTabAction> = EditorTabAction.entries.toSet(),
    val launchCandidatePath: String? = null,
    val launchCandidates: List<LaunchCandidate> = emptyList(),
) {
    val safeSelectedTabIndex: Int
        get() = if (openedFiles.isEmpty()) 0 else selectedTabIndex.coerceIn(0, openedFiles.lastIndex)

    val selectedFile: OpenedFile?
        get() = openedFiles.getOrNull(safeSelectedTabIndex)

    fun file(path: String): OpenedFile? = openedFiles.firstOrNull { it.path == path }

    fun canUndo(path: String): Boolean = history[path]?.undo?.isNotEmpty() == true

    fun canRedo(path: String): Boolean = history[path]?.redo?.isNotEmpty() == true
}

/** An editor tab: the text on the phone, the text last saved to the IDE, and any IDE change that conflicts with it. */
data class OpenedFile(
    val path: String,
    val name: String,
    val text: String,
    val revision: String?,
    val writable: Boolean,
    val isDirty: Boolean = false,
    val savedText: String = text,
    val syncState: FileSyncState = FileSyncState.SYNCED,
    val viewable: Boolean = true,
    val unavailableReason: String? = null,
    val conflictingRemoteRevision: String? = null,
    val conflictingHostText: String? = null,
    val autosave: AutosaveCountdown? = null,
)

enum class FileSyncState {
    SYNCED,
    MODIFIED,
    SAVING,
}

/** Texts to go back and forward to, newest last. */
data class EditHistory(
    val undo: List<String> = emptyList(),
    val redo: List<String> = emptyList(),
)

fun FilesState.contentNeedsRefresh(path: String, nowEpochMillis: Long): Boolean =
    contentFreshness.needsRefresh(path, nowEpochMillis)

fun FilesState.contentCanStartRefresh(path: String): Boolean = contentFreshness.canStartRefresh(path)

fun FilesState.contentRequestKind(path: String): ContentRequestKind =
    if (contentHasRenderableContent(path)) ContentRequestKind.Refresh else ContentRequestKind.InitialLoad

/** Something to show for [path] already: a load happened, or the file has text or an explanation why it has none. */
fun FilesState.contentHasRenderableContent(path: String): Boolean {
    val file = file(path) ?: return false
    return contentFreshness[path]?.hasLoadedOnce == true ||
        file.text.isNotEmpty() ||
        !file.viewable ||
        file.unavailableReason != null
}

/** The selected tab has nothing to show yet and is loading (or about to): a spinner instead of an empty editor. */
fun FilesState.selectedContentShowsLoader(nowEpochMillis: Long): Boolean {
    val path = selectedFile?.path ?: return false
    return !contentHasRenderableContent(path) &&
        (contentFreshness[path]?.refreshState == ContentRefreshState.Loading ||
            contentNeedsRefresh(path, nowEpochMillis))
}

/** The requests the files pane may need for one sync pass. */
enum class FilesRequest {
    EditorTabs,
    SelectedFileContent,
}

fun FilesState.syncRequests(
    nowEpochMillis: Long,
    metadata: Boolean,
    explicitContent: Boolean = false,
): Set<FilesRequest> = buildSet {
    if (metadata) add(FilesRequest.EditorTabs)
    val path = selectedFile?.path ?: return@buildSet
    if (contentCanStartRefresh(path) && (explicitContent || contentNeedsRefresh(path, nowEpochMillis))) {
        add(FilesRequest.SelectedFileContent)
    }
}
