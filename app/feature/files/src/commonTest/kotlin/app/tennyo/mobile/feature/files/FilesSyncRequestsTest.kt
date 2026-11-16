package app.tennyo.mobile.feature.files

import app.tennyo.mobile.link.pane.ContentFreshness
import app.tennyo.mobile.link.pane.ContentRefreshState
import app.tennyo.mobile.link.pane.ContentRequestKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesSyncRequestsTest {
    @Test
    fun metadataPassRefreshesEditorTabsAndTheSelectedFile() {
        val state = FilesState(openedFiles = listOf(openedFile("src/App.kt")))

        assertEquals(
            setOf(FilesRequest.EditorTabs, FilesRequest.SelectedFileContent),
            state.syncRequests(nowEpochMillis = 1_000L, metadata = true),
        )
    }

    @Test
    fun readyLiveSyncStillProbesStaleSelectedContentWithoutMetadataPolling() {
        val state = FilesState(
            openedFiles = listOf(openedFile("src/App.kt")),
            contentFreshness = mapOf("src/App.kt" to ContentFreshness(loadedAtEpochMillis = 1_000L)),
        )

        assertEquals(setOf(FilesRequest.SelectedFileContent), state.syncRequests(nowEpochMillis = 31_001L, metadata = false))
    }

    @Test
    fun selectedContentAlreadyRefreshingDoesNotQueueDuplicateRequest() {
        val state = FilesState(
            openedFiles = listOf(openedFile("src/App.kt")),
            contentFreshness = mapOf("src/App.kt" to ContentFreshness(refreshState = ContentRefreshState.Loading)),
        )

        assertTrue(state.syncRequests(nowEpochMillis = 40_000L, metadata = false, explicitContent = true).isEmpty())
        assertTrue(state.selectedContentShowsLoader(nowEpochMillis = 40_000L))
        assertFalse(state.contentNeedsRefresh("src/App.kt", nowEpochMillis = 40_000L))
    }

    @Test
    fun staleLoadedEmptyFileShowsNoSpinner() {
        val state = FilesState(
            openedFiles = listOf(openedFile("src/Empty.kt")),
            contentFreshness = mapOf("src/Empty.kt" to ContentFreshness(loadedAtEpochMillis = 1_000L)),
        )

        assertFalse(state.selectedContentShowsLoader(nowEpochMillis = 40_000L))
        assertEquals(ContentRequestKind.Refresh, state.contentRequestKind("src/Empty.kt"))
    }

    @Test
    fun failedRenderableRefreshKeepsContentWithoutSpinner() {
        val state = FilesState(
            openedFiles = listOf(openedFile("src/App.kt", text = "existing")),
            contentFreshness = mapOf(
                "src/App.kt" to ContentFreshness(loadedAtEpochMillis = 1_000L, refreshState = ContentRefreshState.Failed),
            ),
        )

        assertFalse(state.selectedContentShowsLoader(nowEpochMillis = 40_000L))
    }

    @Test
    fun undoAndRedoWalkTheEditHistoryAndKeepTheCurrentTextReachable() {
        val edited = reduce(
            reduce(FilesState(openedFiles = listOf(openedFile("a.kt", text = "one"))), FilesMsg.TextEdited("a.kt", "two")),
            FilesMsg.TextEdited("a.kt", "three"),
        )

        val undone = reduce(edited, FilesMsg.Undone("a.kt"))
        assertEquals("two", undone.file("a.kt")?.text)
        assertTrue(undone.canRedo("a.kt"))

        val redone = reduce(undone, FilesMsg.Redone("a.kt"))
        assertEquals("three", redone.file("a.kt")?.text)
        assertFalse(redone.canRedo("a.kt"))
    }

    @Test
    fun closingATabForgetsItsHistory() {
        val edited = reduce(FilesState(openedFiles = listOf(openedFile("a.kt", text = "one"))), FilesMsg.TextEdited("a.kt", "two"))

        assertFalse(reduce(edited, FilesMsg.TabClosed("a.kt")).canUndo("a.kt"))
    }

    private fun openedFile(path: String, text: String = "") =
        OpenedFile(path = path, name = path.substringAfterLast('/'), text = text, revision = null, writable = true, savedText = text)
}
