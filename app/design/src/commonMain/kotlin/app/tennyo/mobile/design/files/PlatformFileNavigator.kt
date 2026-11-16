package app.tennyo.mobile.design.files

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The file tree, as screen 07 shows it: a sheet over the workspace rather than a drawer beside it, on every
 * platform. It sits above [content] so the pane underneath keeps its state while the tree is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlatformFileNavigator(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    navigatorContent: @Composable (closeNavigator: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    content()
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { onOpenChange(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            // The tree fills the screen; zero content insets would put its top under the status bar.
            modifier = modifier.statusBarsPadding(),
            contentWindowInsets = { WindowInsets() },
        ) {
            navigatorContent { onOpenChange(false) }
        }
    }
}
