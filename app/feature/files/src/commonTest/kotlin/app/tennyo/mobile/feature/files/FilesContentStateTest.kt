package app.tennyo.mobile.feature.files

import app.tennyo.contract.EditorTab
import app.tennyo.contract.EditorTabsState
import app.tennyo.contract.FileContent
import app.tennyo.mobile.link.pane.ContentFreshness
import app.tennyo.mobile.link.pane.ContentRefreshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesContentStateTest {
    @Test
    fun remoteSaveResultPreservesEditsTypedWhileSaveWasInFlight() {
        val file = openedFile("src/Main.kt", text = "old")
            .copy(
                text = "old plus new local edit",
                savedText = "old",
                isDirty = true,
                syncState = FileSyncState.MODIFIED,
            )

        val updated = file.withRemoteSaveResult(
            sentText = "old",
            saved = remoteFileContent(
                text = "old",
                revision = "rev-2",
            ),
        )

        assertEquals("old plus new local edit", updated.text)
        assertEquals("old", updated.savedText)
        assertEquals("rev-2", updated.revision)
        assertTrue(updated.isDirty)
        assertEquals(FileSyncState.MODIFIED, updated.syncState)
    }

    @Test
    fun remoteSaveResultMarksFileSyncedWhenNoNewEditsWereTyped() {
        val file = openedFile("src/Main.kt", text = "old")
            .copy(
                savedText = "previous",
                isDirty = true,
                syncState = FileSyncState.SAVING,
            )

        val updated = file.withRemoteSaveResult(
            sentText = "old",
            saved = remoteFileContent(
                text = "old",
                revision = "rev-2",
            ),
        )

        assertEquals("old", updated.text)
        assertEquals("old", updated.savedText)
        assertEquals("rev-2", updated.revision)
        assertFalse(updated.isDirty)
        assertEquals(FileSyncState.SYNCED, updated.syncState)
    }

    @Test
    fun remoteContentRefreshPreservesDirtyDraftWhenIdeRevisionChanged() {
        val file = openedFile("src/Main.kt", text = "draft")
            .copy(
                revision = "rev-1",
                savedText = "base",
                isDirty = true,
                syncState = FileSyncState.MODIFIED,
            )

        val updated = file.withRemoteContentRefresh(
            remoteFileContent(
                text = "ide edit",
                revision = "rev-2",
            )
        )

        assertEquals("draft", updated.text)
        assertEquals("base", updated.savedText)
        assertEquals("rev-1", updated.revision)
        assertEquals("rev-2", updated.conflictingRemoteRevision)
        assertEquals("ide edit", updated.conflictingHostText)
        assertTrue(updated.isDirty)
        assertEquals(FileSyncState.MODIFIED, updated.syncState)
    }

    @Test
    fun remoteContentRefreshAdvancesDirtyDraftBaseWhenIdeContentMatches() {
        val file = openedFile("src/Main.kt", text = "draft")
            .copy(
                revision = "rev-1",
                savedText = "base",
                isDirty = true,
                syncState = FileSyncState.MODIFIED,
            )

        val updated = file.withRemoteContentRefresh(
            remoteFileContent(
                text = "base",
                revision = "rev-2",
            )
        )

        assertEquals("draft", updated.text)
        assertEquals("base", updated.savedText)
        assertEquals("rev-2", updated.revision)
        assertNull(updated.conflictingRemoteRevision)
        assertNull(updated.conflictingHostText)
        assertTrue(updated.isDirty)
        assertEquals(FileSyncState.MODIFIED, updated.syncState)
    }

    @Test
    fun remoteContentRefreshDetectsIdeTextChangeEvenWithoutRevisionChange() {
        val file = openedFile("src/Main.kt", text = "draft")
            .copy(
                revision = "rev-1",
                savedText = "base",
                isDirty = true,
                syncState = FileSyncState.MODIFIED,
            )

        val updated = file.withRemoteContentRefresh(
            remoteFileContent(
                text = "ide edit",
                revision = "rev-1",
            )
        )

        assertEquals("draft", updated.text)
        assertEquals("base", updated.savedText)
        assertEquals("rev-1", updated.revision)
        assertEquals("rev-1", updated.conflictingRemoteRevision)
        assertEquals("ide edit", updated.conflictingHostText)
        assertTrue(updated.isDirty)
        assertEquals(FileSyncState.MODIFIED, updated.syncState)
    }

    @Test
    fun editorMetadataUsesBackendSelectedTabWhenMobileSelectionConflicts() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt"), openedFile("second.kt")),
            selectedTabIndex = 1,
        )

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt"), editorTab("second.kt")),
                selectedPath = "first.kt",
            )
        )

        assertEquals("first.kt", updated.selectedFile?.path)
    }

    @Test
    fun editorMetadataKeepsPendingMobileSelectionUntilIdeConfirms() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt"), openedFile("second.kt")),
            selectedTabIndex = 1,
            pendingEditorSelectionPath = "second.kt",
        )

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt"), editorTab("second.kt")),
                selectedPath = "first.kt",
            )
        )

        assertEquals("second.kt", updated.selectedFile?.path)
        assertEquals("second.kt", updated.pendingEditorSelectionPath)
    }

    @Test
    fun editorMetadataClearsPendingMobileSelectionWhenIdeConfirms() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt"), openedFile("second.kt")),
            selectedTabIndex = 1,
            pendingEditorSelectionPath = "second.kt",
        )

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt"), editorTab("second.kt")),
                selectedPath = "second.kt",
            )
        )

        assertEquals("second.kt", updated.selectedFile?.path)
        assertNull(updated.pendingEditorSelectionPath)
    }

    @Test
    fun editorMetadataFallsBackToBackendSelectionWhenMobileSelectedTabClosed() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt"), openedFile("closed.kt")),
            selectedTabIndex = 1,
        )

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt"), editorTab("second.kt")),
                selectedPath = "second.kt",
            )
        )

        assertEquals("second.kt", updated.selectedFile?.path)
    }

    @Test
    fun optimisticFileOpenCreatesAndSelectsPlaceholderImmediately() {
        val updated = FilesState(
            openedFiles = listOf(openedFile("first.kt")),
        ).let { reduce(it, FilesMsg.OpenRequested("src/NewFile.kt")) }

        assertEquals("src/NewFile.kt", updated.selectedFile?.path)
        assertEquals("NewFile.kt", updated.selectedFile?.name)
        assertEquals("", updated.selectedFile?.text)
    }

    @Test
    fun editorMetadataKeepsOptimisticSelectedFileWhileInitialLoadIsRunning() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt")),
        )
            .let { reduce(it, FilesMsg.OpenRequested("src/NewFile.kt")) }
            .copy(contentFreshness = mapOf(
                "src/NewFile.kt" to ContentFreshness(refreshState = ContentRefreshState.Loading),
            ))

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt")),
                selectedPath = "first.kt",
            )
        )

        assertEquals("src/NewFile.kt", updated.selectedFile?.path)
    }

    @Test
    fun editorMetadataKeepsFailedOptimisticFileOpen() {
        val state = FilesState(
            openedFiles = listOf(openedFile("first.kt")),
        )
            .let { reduce(it, FilesMsg.OpenRequested("src/NewFile.kt")) }
            .copy(contentFreshness = mapOf(
                "src/NewFile.kt" to ContentFreshness(refreshState = ContentRefreshState.Failed),
            ))

        val updated = state.applyEditorTabsState(
            EditorTabsState(
                tabs = listOf(editorTab("first.kt")),
                selectedPath = "first.kt",
            )
        )

        assertEquals("src/NewFile.kt", updated.selectedFile?.path)
    }

    private fun openedFile(path: String, text: String = ""): OpenedFile =
        OpenedFile(
            path = path,
            name = path.substringAfterLast('/'),
            text = text,
            revision = null,
            writable = true,
        )

    private fun remoteFileContent(
        text: String,
        revision: String?,
    ): FileContent =
        FileContent(
            path = REMOTE_FILE_PATH,
            name = REMOTE_FILE_PATH.substringAfterLast('/'),
            text = text,
            revision = revision,
            writable = true,
        )

    private fun editorTab(path: String): EditorTab =
        EditorTab(
            path = path,
            name = path.substringAfterLast('/'),
            writable = true,
        )

    private companion object {
        const val REMOTE_FILE_PATH = "src/Main.kt"
    }
}
