package app.tennyo.mobile.feature.files

import app.tennyo.contract.EditorTab
import app.tennyo.contract.EditorTabsState
import app.tennyo.contract.FileContent
import app.tennyo.contract.FileNode
import app.tennyo.contract.LaunchCandidate
import app.tennyo.mobile.design.AutosaveCountdown
import app.tennyo.mobile.link.pane.ContentRefreshState
import app.tennyo.mobile.link.pane.withClearedRefresh
import app.tennyo.mobile.link.pane.withLoaded
import app.tennyo.mobile.link.pane.withRefreshFailed
import app.tennyo.mobile.link.pane.withRefreshStarted

/** Every change to [FilesState]; applied only through [reduce]. */
internal sealed interface FilesMsg {
    data class TreeLoaded(val root: FileNode) : FilesMsg
    data class FolderLoaded(val subtree: FileNode) : FilesMsg

    /** The user opened [path]: its tab appears and selects at once, loading until the content arrives. */
    data class OpenRequested(val path: String) : FilesMsg
    data class Opened(val content: FileContent, val preferredSelectedPath: String?, val loadedAt: Long) : FilesMsg
    data class TabSelected(val index: Int, val pendingPath: String?) : FilesMsg
    data class PendingSelectionCleared(val path: String) : FilesMsg
    data class TabClosed(val path: String) : FilesMsg

    /** The IDE no longer has [path]: its tab closes as if the user had closed it. */
    data class FileMissing(val path: String) : FilesMsg
    data class TextEdited(val path: String, val text: String) : FilesMsg
    data class Undone(val path: String) : FilesMsg
    data class Redone(val path: String) : FilesMsg
    data class AutosaveScheduled(val path: String, val startedAt: Long, val durationMillis: Long) : FilesMsg
    data class AutosaveDropped(val path: String) : FilesMsg
    data class SyncStateChanged(val path: String, val syncState: FileSyncState) : FilesMsg
    data class Saved(val sentText: String, val saved: FileContent, val loadedAt: Long) : FilesMsg
    data class EditorTabsReceived(val tabs: EditorTabsState) : FilesMsg
    data class ContentReceived(val content: FileContent, val loadedAt: Long) : FilesMsg
    data class ContentRefreshStarted(val path: String) : FilesMsg
    data class ContentRefreshFailed(val path: String) : FilesMsg
    data class ContentRefreshCleared(val path: String) : FilesMsg
    data class LaunchCandidatesReceived(val path: String, val candidates: List<LaunchCandidate>) : FilesMsg
}

internal fun reduce(state: FilesState, msg: FilesMsg): FilesState = when (msg) {
    is FilesMsg.TreeLoaded -> state.copy(root = msg.root)
    is FilesMsg.FolderLoaded -> state.copy(root = state.root?.mergeSubtree(msg.subtree) ?: msg.subtree)
    is FilesMsg.OpenRequested -> state.withOptimisticOpenedFile(msg.path).let {
        it.copy(
            contentFreshness = it.contentFreshness.withRefreshStarted(msg.path, it.contentRequestKind(msg.path)),
            launchCandidatePath = msg.path,
            launchCandidates = emptyList(),
        )
    }

    is FilesMsg.Opened -> state.withOpened(msg.content, msg.preferredSelectedPath, msg.loadedAt)
    is FilesMsg.TabSelected -> state.copy(
        selectedTabIndex = if (state.openedFiles.isEmpty()) 0 else msg.index.coerceIn(0, state.openedFiles.lastIndex),
        pendingEditorSelectionPath = msg.pendingPath,
    ).let { selected ->
        val path = selected.selectedFile?.path
        selected.copy(launchCandidatePath = path, launchCandidates = emptyList())
    }

    is FilesMsg.PendingSelectionCleared -> state.copy(
        pendingEditorSelectionPath = state.pendingEditorSelectionPath.takeIf { it != msg.path },
    )

    is FilesMsg.TabClosed -> state.withoutTab(msg.path, onlyIfOpen = true)
    is FilesMsg.FileMissing -> state.withoutTab(msg.path, onlyIfOpen = false)
    is FilesMsg.TextEdited -> state.withEdit(msg.path, msg.text)
    is FilesMsg.Undone -> state.withHistoryStep(msg.path, back = true)
    is FilesMsg.Redone -> state.withHistoryStep(msg.path, back = false)
    is FilesMsg.AutosaveScheduled -> state.mapFile(msg.path) { file ->
        file.withScheduledAutosave(msg.startedAt, msg.durationMillis)
    }

    is FilesMsg.AutosaveDropped -> state.mapFile(msg.path) { it.copy(autosave = null) }
    is FilesMsg.SyncStateChanged -> state.mapFile(msg.path) { file ->
        file.copy(
            syncState = msg.syncState,
            autosave = file.autosave.takeIf { msg.syncState == FileSyncState.MODIFIED },
        )
    }

    is FilesMsg.Saved -> state.mapFile(msg.saved.path) { it.withRemoteSaveResult(msg.sentText, msg.saved) }
        .let { it.copy(contentFreshness = it.contentFreshness.withLoaded(msg.saved.path, msg.loadedAt)) }

    is FilesMsg.EditorTabsReceived -> state.applyEditorTabsState(msg.tabs)
    is FilesMsg.ContentReceived -> state.withContent(msg.content, msg.loadedAt)
    is FilesMsg.ContentRefreshStarted -> state.copy(
        contentFreshness = state.contentFreshness.withRefreshStarted(msg.path, state.contentRequestKind(msg.path)),
    )

    is FilesMsg.ContentRefreshFailed ->
        state.copy(contentFreshness = state.contentFreshness.withRefreshFailed(msg.path))
    is FilesMsg.ContentRefreshCleared ->
        state.copy(contentFreshness = state.contentFreshness.withClearedRefresh(msg.path))
    is FilesMsg.LaunchCandidatesReceived -> if (state.selectedFile?.path == msg.path) {
        state.copy(launchCandidatePath = msg.path, launchCandidates = msg.candidates)
    } else state
}

private fun FilesState.mapFile(path: String, transform: (OpenedFile) -> OpenedFile): FilesState =
    copy(openedFiles = openedFiles.map { if (it.path == path) transform(it) else it })

private fun FilesState.withOptimisticOpenedFile(path: String): FilesState {
    val existingIndex = openedFiles.indexOfFirst { it.path == path }
    val updatedFiles = if (existingIndex >= 0) {
        openedFiles
    } else {
        openedFiles + OpenedFile(
            path = path,
            name = path.substringAfterLast('/').ifBlank { path },
            text = "",
            revision = null,
            writable = true,
        )
    }
    val selectedIndex = if (existingIndex >= 0) existingIndex else updatedFiles.lastIndex
    return copy(openedFiles = updatedFiles, selectedTabIndex = selectedIndex)
}

private fun FilesState.withOpened(content: FileContent, preferredSelectedPath: String?, loadedAt: Long): FilesState {
    val opened = content.toOpenedFile()
    val existingIndex = openedFiles.indexOfFirst { it.path == content.path }
    val files = if (existingIndex == -1) {
        openedFiles + opened
    } else {
        openedFiles.toMutableList().apply { set(existingIndex, opened) }
    }
    val openedIndex = if (existingIndex == -1) files.lastIndex else existingIndex
    val selectedIndex = when {
        preferredSelectedPath == content.path -> openedIndex
        preferredSelectedPath != null -> files.indexOfFirst { it.path == preferredSelectedPath }.takeIf { it >= 0 }
            ?: safeSelectedTabIndex.coerceAtMost(files.lastIndex.coerceAtLeast(0))

        else -> openedIndex
    }
    return copy(
        openedFiles = files,
        selectedTabIndex = selectedIndex,
        contentFreshness = contentFreshness.withLoaded(content.path, loadedAt),
    )
}

/** Removes [path]'s tab, keeping the selection on the same file where it can; [onlyIfOpen] ignores unknown paths. */
private fun FilesState.withoutTab(path: String, onlyIfOpen: Boolean): FilesState {
    val index = openedFiles.indexOfFirst { it.path == path }
    if (index == -1 && onlyIfOpen) return this
    val files = openedFiles.filterNot { it.path == path }
    val selected = when {
        files.isEmpty() -> 0
        index < 0 -> safeSelectedTabIndex.coerceAtMost(files.lastIndex)
        safeSelectedTabIndex > index -> safeSelectedTabIndex - 1
        safeSelectedTabIndex == index -> index.coerceAtMost(files.lastIndex)
        else -> safeSelectedTabIndex.coerceAtMost(files.lastIndex)
    }
    return copy(
        openedFiles = files,
        contentFreshness = contentFreshness - path,
        selectedTabIndex = selected,
        pendingEditorSelectionPath = pendingEditorSelectionPath.takeIf { it != path },
        history = history - path,
    )
}

private fun FilesState.withEdit(path: String, text: String): FilesState {
    val file = file(path)?.takeIf { it.viewable && it.text != text } ?: return this
    val previous = history[path] ?: EditHistory()
    return copy(history = history + (path to EditHistory(undo = previous.undo + file.text))).withText(path, text)
}

/** Moves one step [back] (undo) or forward (redo) through [path]'s history, keeping the current text reachable. */
private fun FilesState.withHistoryStep(path: String, back: Boolean): FilesState {
    val file = file(path)?.takeIf { it.viewable } ?: return this
    val entry = history[path] ?: return this
    val target = (if (back) entry.undo else entry.redo).lastOrNull() ?: return this
    val next = if (back) {
        EditHistory(undo = entry.undo.dropLast(1), redo = entry.redo + file.text)
    } else {
        EditHistory(undo = entry.undo + file.text, redo = entry.redo.dropLast(1))
    }
    return copy(history = history + (path to next)).withText(path, target)
}

private fun FilesState.withText(path: String, text: String): FilesState = mapFile(path) { file ->
    val nextDirty = text != file.savedText
    file.copy(
        text = text,
        isDirty = nextDirty,
        syncState = if (nextDirty) FileSyncState.MODIFIED else FileSyncState.SYNCED,
        autosave = file.autosave.takeIf { nextDirty },
    )
}

/** Content the IDE pushed or returned for a refresh; a tab with unsaved edits keeps them and records any conflict. */
private fun FilesState.withContent(content: FileContent, loadedAt: Long): FilesState {
    val hasExistingFile = openedFiles.any { it.path == content.path }
    val files = openedFiles.map { file ->
        when {
            file.path != content.path -> file
            file.syncState == FileSyncState.MODIFIED || file.syncState == FileSyncState.SAVING ->
                file.withRemoteContentRefresh(content)

            else -> content.toOpenedFile().copy(name = file.name)
        }
    }.let { if (hasExistingFile) it else it + content.toOpenedFile() }
    return copy(
        openedFiles = files,
        contentFreshness = contentFreshness.withLoaded(content.path, loadedAt),
        selectedTabIndex = selectedTabIndex.takeIf { it in files.indices } ?: files.lastIndex.coerceAtLeast(0),
    )
}

/**
 * The IDE's tab list wins, except for a tab the phone just opened or selected and the IDE has not reported yet: an
 * optimistic tab still loading (or failed) survives, and a pending selection is kept until the IDE confirms it.
 */
internal fun FilesState.applyEditorTabsState(tabsState: EditorTabsState): FilesState {
    val remotePaths = tabsState.tabs.mapTo(mutableSetOf()) { it.path }
    val existingByPath = openedFiles.associateBy { it.path }
    val optimisticSelectedFile = selectedFile?.takeIf { file ->
        val freshness = contentFreshness[file.path]
        file.path !in remotePaths &&
            freshness != null &&
            freshness.loadedAtEpochMillis == 0L &&
            (freshness.refreshState == ContentRefreshState.Loading ||
                freshness.refreshState == ContentRefreshState.Failed)
    }
    val files = tabsState.tabs.map { tab -> existingByPath[tab.path] ?: tab.toPlaceholderOpenedFile() } +
        listOfNotNull(optimisticSelectedFile)
    val selectablePaths = files.mapTo(mutableSetOf()) { it.path }
    val mobileSelectedPath = selectedFile?.path?.takeIf { it in selectablePaths }
    val optimisticSelectedPath = optimisticSelectedFile?.path?.takeIf { it == mobileSelectedPath }
    val backendSelectedPath = tabsState.selectedPath?.takeIf { it in selectablePaths }
    val pendingSelectedPath = pendingEditorSelectionPath?.takeIf { it in selectablePaths }
    val selectedPath = optimisticSelectedPath
        ?: pendingSelectedPath
        ?: backendSelectedPath
        ?: mobileSelectedPath
        ?: files.getOrNull(safeSelectedTabIndex)?.path
    val pendingSelection = pendingEditorSelectionPath?.takeUnless { pending ->
        pending == backendSelectedPath || pending !in selectablePaths
    }
    val selectedIndex = selectedPath?.let { path -> files.indexOfFirst { it.path == path }.takeIf { it >= 0 } }
        ?: safeSelectedTabIndex.coerceAtMost(files.lastIndex.coerceAtLeast(0))
    return copy(
        openedFiles = files,
        contentFreshness = contentFreshness.filterKeys { it in selectablePaths },
        selectedTabIndex = selectedIndex,
        pendingEditorSelectionPath = pendingSelection,
        history = history.filterKeys { it in selectablePaths },
        allowedTabActions = tabsState.allowedActions,
    )
}

/** A save the IDE accepted; text typed while it was in flight stays as a new unsaved edit. */
internal fun OpenedFile.withRemoteSaveResult(sentText: String, saved: FileContent): OpenedFile {
    val hasChangedSinceSaveStarted = text != sentText
    val nextText = if (hasChangedSinceSaveStarted) text else saved.text
    val nextDirty = nextText != saved.text
    return copy(
        text = nextText,
        revision = saved.revision,
        writable = saved.writable,
        viewable = saved.viewable,
        unavailableReason = saved.unavailableReason,
        isDirty = nextDirty,
        savedText = saved.text,
        syncState = if (nextDirty) FileSyncState.MODIFIED else FileSyncState.SYNCED,
        conflictingRemoteRevision = null,
        conflictingHostText = null,
        autosave = autosave.takeIf { nextDirty },
    )
}

/**
 * New IDE content for a tab with unsaved edits: if it still matches what the phone last saved, the base advances;
 * otherwise the draft stays and the IDE's version is kept aside as a conflict.
 */
internal fun OpenedFile.withRemoteContentRefresh(content: FileContent): OpenedFile {
    val hasRemoteConflict = content.text != savedText
    val nextSavedText = if (hasRemoteConflict) savedText else content.text
    val nextDirty = text != nextSavedText
    return copy(
        revision = if (hasRemoteConflict) revision else content.revision,
        writable = content.writable,
        viewable = content.viewable,
        unavailableReason = content.unavailableReason,
        isDirty = nextDirty,
        savedText = nextSavedText,
        syncState = when {
            syncState == FileSyncState.SAVING -> FileSyncState.SAVING
            nextDirty -> FileSyncState.MODIFIED
            else -> FileSyncState.SYNCED
        },
        conflictingRemoteRevision = content.revision.takeIf { hasRemoteConflict },
        conflictingHostText = content.text.takeIf { hasRemoteConflict },
        autosave = autosave.takeIf { nextDirty && syncState != FileSyncState.SAVING },
    )
}

internal fun OpenedFile.withScheduledAutosave(startedAtEpochMillis: Long, durationMillis: Long): OpenedFile {
    if (!isDirty) return copy(autosave = null)
    return copy(
        autosave = AutosaveCountdown(
            startedAtEpochMillis = startedAtEpochMillis,
            durationMillis = durationMillis,
            generation = (autosave?.generation ?: 0L) + 1L,
        ),
    )
}

internal fun FileContent.toOpenedFile(): OpenedFile =
    OpenedFile(
        path = path,
        name = name,
        text = text,
        revision = revision,
        writable = writable,
        savedText = text,
        syncState = FileSyncState.SYNCED,
        viewable = viewable,
        unavailableReason = unavailableReason,
    )

private fun EditorTab.toPlaceholderOpenedFile(): OpenedFile =
    OpenedFile(path = path, name = name, text = "", revision = null, writable = writable, savedText = "")

private fun FileNode.mergeSubtree(subtree: FileNode): FileNode {
    if (path == subtree.path) return subtree.copy(name = name.ifBlank { subtree.name })
    return copy(
        children = children.map { child ->
            if (subtree.path == child.path || subtree.path.startsWith(child.path.withTrailingSlash())) {
                child.mergeSubtree(subtree)
            } else {
                child
            }
        },
    )
}

private fun String.withTrailingSlash(): String = if (isBlank() || endsWith('/')) this else "$this/"
