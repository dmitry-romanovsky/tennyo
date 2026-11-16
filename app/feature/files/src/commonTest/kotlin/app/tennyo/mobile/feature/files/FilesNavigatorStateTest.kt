package app.tennyo.mobile.feature.files

import app.tennyo.contract.FileNode
import app.tennyo.mobile.design.AutosaveCountdown
import app.tennyo.mobile.design.SavePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesNavigatorStateTest {
    @Test
    fun compactPackageFolderUsesVisibleParentInsteadOfStringParent() {
        val state = projectTree().toProjectFilesScreenState(
            currentFolderId = "src/main/java/org/springframework/samples/petclinic",
            selectedId = null,
            labels = testLabels(),
        )

        assertEquals("src/main/java", state.parentId)
    }

    @Test
    fun childInsideCompactPackageFolderNavigatesUpToCompactFolder() {
        val state = projectTree().toProjectFilesScreenState(
            currentFolderId = "src/main/java/org/springframework/samples/petclinic/model",
            selectedId = null,
            labels = testLabels(),
        )

        assertEquals("src/main/java/org/springframework/samples/petclinic", state.parentId)
    }

    @Test
    fun dirtyFileWithDebounceShowsDebouncingSaveButtonState() {
        val state = openedFile(
            isDirty = true,
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 1L,
            ),
        ).savePhase()

        // Unsaved is the phase whose border fills with the countdown.
        assertEquals(SavePhase.UNSAVED, state)
    }

    @Test
    fun savingFileHidesDebounceFill() {
        val state = openedFile(
            isDirty = true,
            syncState = FileSyncState.SAVING,
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 1L,
            ),
        ).savePhase()

        assertEquals(SavePhase.SAVING, state)
    }

    @Test
    fun nonWritableFileShowsDisabledSaveButtonStateWithoutFill() {
        val state = openedFile(
            isDirty = true,
            writable = false,
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 1L,
            ),
        ).savePhase()

        assertEquals(SavePhase.SAVED, state)
    }

    private fun testLabels() = FilesLabels(
        filesTitle = "Files",
        projectRoot = "Project root",
        emptyLoaded = "Empty",
        emptyFolder = "No files",
    )

    private fun openedFile(
        isDirty: Boolean = false,
        writable: Boolean = true,
        syncState: FileSyncState = FileSyncState.MODIFIED,
        autosave: AutosaveCountdown? = null,
    ) = OpenedFile(
        path = "src/main/kotlin/Main.kt",
        name = "Main.kt",
        text = if (isDirty) "draft" else "saved",
        revision = "1",
        writable = writable,
        isDirty = isDirty,
        savedText = "saved",
        syncState = syncState,
        autosave = autosave,
    )

    private fun projectTree() = FileNode(
        path = "",
        name = "petclinic",
        isDirectory = true,
        children = listOf(
            FileNode(
                path = "src",
                name = "src",
                isDirectory = true,
                children = listOf(
                    FileNode(
                        path = "src/main",
                        name = "main",
                        isDirectory = true,
                        children = listOf(
                            FileNode(
                                path = "src/main/java",
                                name = "java",
                                isDirectory = true,
                                children = listOf(
                                    FileNode(
                                        path = "src/main/java/org/springframework/samples/petclinic",
                                        name = "org.springframework.samples.petclinic",
                                        isDirectory = true,
                                        children = listOf(
                                            FileNode(
                                                path = "src/main/java/org/springframework/samples/petclinic/model",
                                                name = "model",
                                                isDirectory = true,
                                                children = listOf(
                                                    FileNode(
                                                        path = "src/main/java/org/springframework/samples/petclinic/model/BaseEntity.java",
                                                        name = "BaseEntity.java",
                                                        isDirectory = false,
                                                    )
                                                ),
                                            )
                                        ),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        ),
    )
}
