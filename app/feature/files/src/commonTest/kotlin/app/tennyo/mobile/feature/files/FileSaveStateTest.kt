package app.tennyo.mobile.feature.files

import app.tennyo.contract.FileContent
import app.tennyo.mobile.design.AutosaveCountdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileSaveStateTest {
    @Test
    fun schedulingAutosaveAddsDebounceMetadata() {
        val updated = dirtyFile().withScheduledAutosave(
            startedAtEpochMillis = 1_000L,
            durationMillis = 3_000L,
        )

        val debounce = requireNotNull(updated.autosave)
        assertEquals(1_000L, debounce.startedAtEpochMillis)
        assertEquals(3_000L, debounce.durationMillis)
        assertEquals(1L, debounce.generation)
    }

    @Test
    fun schedulingAutosaveAgainIncrementsGeneration() {
        val updated = dirtyFile(
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 1L,
            ),
        ).withScheduledAutosave(
            startedAtEpochMillis = 2_000L,
            durationMillis = 3_000L,
        )

        val debounce = requireNotNull(updated.autosave)
        assertEquals(2L, debounce.generation)
        assertEquals(2_000L, debounce.startedAtEpochMillis)
    }

    @Test
    fun returningEditorTextToSavedTextClearsDebounceMetadata() {
        val file = dirtyFile(
            text = "draft",
            savedText = "saved",
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 3L,
            ),
        )
        val updated = file.copy(
            text = "saved",
            isDirty = false,
            syncState = FileSyncState.SYNCED,
            autosave = null,
        )

        assertEquals(FileSyncState.SYNCED, updated.syncState)
        assertNull(updated.autosave)
    }

    @Test
    fun savingStateClearsDebounceMetadata() {
        val file = dirtyFile(
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 1_000L,
                durationMillis = 3_000L,
                generation = 1L,
            ),
        )
        val updated = file.copy(
            syncState = FileSyncState.SAVING,
            autosave = null,
        )

        assertEquals(FileSyncState.SAVING, updated.syncState)
        assertNull(updated.autosave)
    }

    @Test
    fun remoteSaveResultKeepsNewDebounceWhenFileChangedDuringSave() {
        val updated = dirtyFile(
            text = "newest",
            savedText = "base",
            autosave = AutosaveCountdown(
                startedAtEpochMillis = 2_000L,
                durationMillis = 3_000L,
                generation = 4L,
            ),
        ).withRemoteSaveResult(
            sentText = "older",
            saved = FileContent(
                path = "src/Main.kt",
                name = "Main.kt",
                text = "older",
                revision = "2",
            ),
        )

        assertEquals(FileSyncState.MODIFIED, updated.syncState)
        assertNotNull(updated.autosave)
        assertEquals("newest", updated.text)
        assertEquals("older", updated.savedText)
    }

    private fun dirtyFile(
        text: String = "draft",
        savedText: String = "saved",
        autosave: AutosaveCountdown? = null,
    ) = OpenedFile(
        path = "src/Main.kt",
        name = "Main.kt",
        text = text,
        revision = "1",
        writable = true,
        isDirty = text != savedText,
        savedText = savedText,
        syncState = FileSyncState.MODIFIED,
        autosave = autosave,
    )
}
