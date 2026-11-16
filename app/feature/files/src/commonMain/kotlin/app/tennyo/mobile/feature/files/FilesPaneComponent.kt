package app.tennyo.mobile.feature.files

import app.tennyo.mobile.design.PaneNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The files pane: browse the project tree and edit files in tabs mirrored from the IDE's editor. */
interface FilesPaneComponent {
    val state: StateFlow<FilesState>

    /** Consumed by the workspace, which shows them in its message area and banner. */
    val notices: Flow<PaneNotice>

    fun onIntent(intent: FilesIntent)
}

sealed interface FilesIntent {
    data class LoadFolder(val path: String) : FilesIntent
    data class OpenFile(val path: String) : FilesIntent
    data class SelectTab(val index: Int) : FilesIntent
    data class CloseTab(val path: String) : FilesIntent
    data object RefreshSelectedFile : FilesIntent
    data class EditText(val path: String, val text: String) : FilesIntent
    data object SaveSelectedFile : FilesIntent
    data object Undo : FilesIntent
    data object Redo : FilesIntent
    data class LaunchConfiguration(val id: String) : FilesIntent
    data class StopLaunchConfiguration(val id: String) : FilesIntent
}
