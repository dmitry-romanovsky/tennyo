package app.tennyo.mobile.feature.files

import app.tennyo.mobile.feature.files.generated.resources.Res
import app.tennyo.mobile.feature.files.generated.resources.close_editor_tab_failed
import app.tennyo.mobile.feature.files.generated.resources.load_editor_tabs_failed
import app.tennyo.mobile.feature.files.generated.resources.load_files_failed
import app.tennyo.mobile.feature.files.generated.resources.load_folder_failed
import app.tennyo.mobile.feature.files.generated.resources.open_file_failed
import app.tennyo.mobile.feature.files.generated.resources.save_file_failed
import app.tennyo.mobile.feature.files.generated.resources.select_editor_tab_failed
import app.tennyo.mobile.feature.files.generated.resources.start_launch_failed
import app.tennyo.mobile.feature.files.generated.resources.stop_launch_failed
import org.jetbrains.compose.resources.StringResource

/** A files request that can fail on its own without ending the session, with how its failure is worded. */
internal enum class FilesAction(val failure: StringResource) {
    LoadFiles(Res.string.load_files_failed),
    LoadEditorTabs(Res.string.load_editor_tabs_failed),
    OpenFile(Res.string.open_file_failed),
    LoadFolder(Res.string.load_folder_failed),
    SelectEditorTab(Res.string.select_editor_tab_failed),
    CloseEditorTab(Res.string.close_editor_tab_failed),
    SaveFile(Res.string.save_file_failed),
    StartLaunch(Res.string.start_launch_failed),
    StopLaunch(Res.string.stop_launch_failed),
}
