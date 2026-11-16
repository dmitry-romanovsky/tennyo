package app.tennyo.mobile.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.ic_account_tree
import app.tennyo.mobile.design.generated.resources.ic_add
import app.tennyo.mobile.design.generated.resources.ic_arrow_back
import app.tennyo.mobile.design.generated.resources.ic_arrow_downward
import app.tennyo.mobile.design.generated.resources.ic_arrow_upward
import app.tennyo.mobile.design.generated.resources.ic_call_split
import app.tennyo.mobile.design.generated.resources.ic_check
import app.tennyo.mobile.design.generated.resources.ic_chevron_right
import app.tennyo.mobile.design.generated.resources.ic_close
import app.tennyo.mobile.design.generated.resources.ic_cloud_upload
import app.tennyo.mobile.design.generated.resources.ic_code
import app.tennyo.mobile.design.generated.resources.ic_commit
import app.tennyo.mobile.design.generated.resources.ic_content_copy
import app.tennyo.mobile.design.generated.resources.ic_delete
import app.tennyo.mobile.design.generated.resources.ic_description
import app.tennyo.mobile.design.generated.resources.ic_download
import app.tennyo.mobile.design.generated.resources.ic_edit
import app.tennyo.mobile.design.generated.resources.ic_error_outline
import app.tennyo.mobile.design.generated.resources.ic_folder
import app.tennyo.mobile.design.generated.resources.ic_folder_open
import app.tennyo.mobile.design.generated.resources.ic_forum
import app.tennyo.mobile.design.generated.resources.ic_help
import app.tennyo.mobile.design.generated.resources.ic_info
import app.tennyo.mobile.design.generated.resources.ic_insert_drive_file
import app.tennyo.mobile.design.generated.resources.ic_keyboard
import app.tennyo.mobile.design.generated.resources.ic_keyboard_arrow_down
import app.tennyo.mobile.design.generated.resources.ic_keyboard_arrow_right
import app.tennyo.mobile.design.generated.resources.ic_lightbulb
import app.tennyo.mobile.design.generated.resources.ic_link
import app.tennyo.mobile.design.generated.resources.ic_more_horiz
import app.tennyo.mobile.design.generated.resources.ic_more_vert
import app.tennyo.mobile.design.generated.resources.ic_navigate_before
import app.tennyo.mobile.design.generated.resources.ic_navigate_next
import app.tennyo.mobile.design.generated.resources.ic_network_wifi3_bar
import app.tennyo.mobile.design.generated.resources.ic_palette
import app.tennyo.mobile.design.generated.resources.ic_play_arrow
import app.tennyo.mobile.design.generated.resources.ic_redo
import app.tennyo.mobile.design.generated.resources.ic_refresh
import app.tennyo.mobile.design.generated.resources.ic_remove
import app.tennyo.mobile.design.generated.resources.ic_replay
import app.tennyo.mobile.design.generated.resources.ic_save
import app.tennyo.mobile.design.generated.resources.ic_search
import app.tennyo.mobile.design.generated.resources.ic_send
import app.tennyo.mobile.design.generated.resources.ic_settings
import app.tennyo.mobile.design.generated.resources.ic_signal_wifi_bad
import app.tennyo.mobile.design.generated.resources.ic_stop
import app.tennyo.mobile.design.generated.resources.ic_sync
import app.tennyo.mobile.design.generated.resources.ic_tennyo_mark
import app.tennyo.mobile.design.generated.resources.ic_terminal
import app.tennyo.mobile.design.generated.resources.ic_undo
import app.tennyo.mobile.design.generated.resources.ic_upload
import app.tennyo.mobile.design.generated.resources.ic_wifi
import app.tennyo.mobile.design.generated.resources.ic_wifi_find
import app.tennyo.mobile.design.generated.resources.ic_wifi_off
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The app's icon set: 24dp vector drawables in this module's resources, one per glyph the UI uses. Screens read the
 * [ImageVector]s; code that picks an icon outside composition keeps the [DrawableResource] from [TennyoIcons.Drawables].
 */
object TennyoIcons {
    val AccountTree: ImageVector @Composable get() = vectorResource(Res.drawable.ic_account_tree)
    val Add: ImageVector @Composable get() = vectorResource(Res.drawable.ic_add)
    val ArrowBack: ImageVector @Composable get() = vectorResource(Res.drawable.ic_arrow_back)
    val ArrowDownward: ImageVector @Composable get() = vectorResource(Res.drawable.ic_arrow_downward)
    val ArrowUpward: ImageVector @Composable get() = vectorResource(Res.drawable.ic_arrow_upward)
    val CallSplit: ImageVector @Composable get() = vectorResource(Res.drawable.ic_call_split)
    val Check: ImageVector @Composable get() = vectorResource(Res.drawable.ic_check)

    /** The Tennyo orbit mark, the launcher glyph as an icon. */
    val Brand: ImageVector @Composable get() = vectorResource(Res.drawable.ic_tennyo_mark)
    val ChevronRight: ImageVector @Composable get() = vectorResource(Res.drawable.ic_chevron_right)
    val Close: ImageVector @Composable get() = vectorResource(Res.drawable.ic_close)
    val CloudUpload: ImageVector @Composable get() = vectorResource(Res.drawable.ic_cloud_upload)
    val Code: ImageVector @Composable get() = vectorResource(Res.drawable.ic_code)
    val Commit: ImageVector @Composable get() = vectorResource(Res.drawable.ic_commit)
    val ContentCopy: ImageVector @Composable get() = vectorResource(Res.drawable.ic_content_copy)
    val Description: ImageVector @Composable get() = vectorResource(Res.drawable.ic_description)
    val Download: ImageVector @Composable get() = vectorResource(Res.drawable.ic_download)
    val ErrorOutline: ImageVector @Composable get() = vectorResource(Res.drawable.ic_error_outline)
    val Edit: ImageVector @Composable get() = vectorResource(Res.drawable.ic_edit)
    val Forum: ImageVector @Composable get() = vectorResource(Res.drawable.ic_forum)
    val Help: ImageVector @Composable get() = vectorResource(Res.drawable.ic_help)
    val MoreHoriz: ImageVector @Composable get() = vectorResource(Res.drawable.ic_more_horiz)
    val Lightbulb: ImageVector @Composable get() = vectorResource(Res.drawable.ic_lightbulb)
    val Send: ImageVector @Composable get() = vectorResource(Res.drawable.ic_send)
    val Terminal: ImageVector @Composable get() = vectorResource(Res.drawable.ic_terminal)
    val Folder: ImageVector @Composable get() = vectorResource(Res.drawable.ic_folder)
    val FolderOpen: ImageVector @Composable get() = vectorResource(Res.drawable.ic_folder_open)
    val Info: ImageVector @Composable get() = vectorResource(Res.drawable.ic_info)
    val InsertDriveFile: ImageVector @Composable get() = vectorResource(Res.drawable.ic_insert_drive_file)
    val Keyboard: ImageVector @Composable get() = vectorResource(Res.drawable.ic_keyboard)
    val KeyboardArrowDown: ImageVector @Composable get() = vectorResource(Res.drawable.ic_keyboard_arrow_down)
    val KeyboardArrowRight: ImageVector @Composable get() = vectorResource(Res.drawable.ic_keyboard_arrow_right)
    val Link: ImageVector @Composable get() = vectorResource(Res.drawable.ic_link)
    val MoreVert: ImageVector @Composable get() = vectorResource(Res.drawable.ic_more_vert)
    val NavigateBefore: ImageVector @Composable get() = vectorResource(Res.drawable.ic_navigate_before)
    val NavigateNext: ImageVector @Composable get() = vectorResource(Res.drawable.ic_navigate_next)
    val NetworkWifi3Bar: ImageVector @Composable get() = vectorResource(Res.drawable.ic_network_wifi3_bar)
    val Palette: ImageVector @Composable get() = vectorResource(Res.drawable.ic_palette)
    val PlayArrow: ImageVector @Composable get() = vectorResource(Res.drawable.ic_play_arrow)
    val Redo: ImageVector @Composable get() = vectorResource(Res.drawable.ic_redo)
    val Refresh: ImageVector @Composable get() = vectorResource(Res.drawable.ic_refresh)
    val Remove: ImageVector @Composable get() = vectorResource(Res.drawable.ic_remove)
    val Replay: ImageVector @Composable get() = vectorResource(Res.drawable.ic_replay)
    val Save: ImageVector @Composable get() = vectorResource(Res.drawable.ic_save)
    val Search: ImageVector @Composable get() = vectorResource(Res.drawable.ic_search)
    val Settings: ImageVector @Composable get() = vectorResource(Res.drawable.ic_settings)
    val SignalWifiBad: ImageVector @Composable get() = vectorResource(Res.drawable.ic_signal_wifi_bad)
    val Stop: ImageVector @Composable get() = vectorResource(Res.drawable.ic_stop)
    val Sync: ImageVector @Composable get() = vectorResource(Res.drawable.ic_sync)
    val Delete: ImageVector @Composable get() = vectorResource(Res.drawable.ic_delete)
    val Undo: ImageVector @Composable get() = vectorResource(Res.drawable.ic_undo)
    val Upload: ImageVector @Composable get() = vectorResource(Res.drawable.ic_upload)
    val Wifi: ImageVector @Composable get() = vectorResource(Res.drawable.ic_wifi)
    val WifiFind: ImageVector @Composable get() = vectorResource(Res.drawable.ic_wifi_find)
    val WifiOff: ImageVector @Composable get() = vectorResource(Res.drawable.ic_wifi_off)

    object Drawables {
        val AccountTree: DrawableResource get() = Res.drawable.ic_account_tree
        val Brand: DrawableResource get() = Res.drawable.ic_tennyo_mark
        val Keyboard: DrawableResource get() = Res.drawable.ic_keyboard
        val Palette: DrawableResource get() = Res.drawable.ic_palette
    }
}
