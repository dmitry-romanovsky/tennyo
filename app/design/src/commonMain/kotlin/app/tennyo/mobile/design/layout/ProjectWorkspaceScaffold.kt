package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tennyo.mobile.design.files.PlatformFileNavigator

/**
 * A project screen: a compact top bar with [actions] and [banner], the optional [navigationStrip] under it, and
 * [content] below. With [navigatorContent], [navigatorOpen] shows it in the platform file navigator sheet.
 *
 * @param navigationStrip the destination strip and its overflow; omitted when there is nothing to navigate.
 */
@Composable
fun ProjectWorkspaceScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navigationStrip: (@Composable () -> Unit)? = null,
    navigatorOpen: Boolean = false,
    onNavigatorOpenChange: (Boolean) -> Unit = {},
    navigatorContent: (@Composable (closeNavigator: () -> Unit) -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    banner: @Composable () -> Unit = {},
    content: @Composable (innerPadding: PaddingValues) -> Unit,
) {
    val scaffold: @Composable () -> Unit = {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            // The strip rides under the toolbar, not along the bottom: the composer and the phone's own gesture
            // area own that edge, and a destination row there competes with both.
            topBar = {
                Column {
                    PlatformTopAppBar(
                        title = title,
                        onBack = onBack,
                        actions = actions,
                        modifier = Modifier.fillMaxWidth(),
                        size = PlatformTopAppBarSize.Compact,
                        bottomContent = banner,
                    )
                    navigationStrip?.invoke()
                }
            },
            contentWindowInsets = WindowInsets(),
            content = content,
        )
    }
    if (navigatorContent == null) {
        scaffold()
    } else {
        PlatformFileNavigator(
            open = navigatorOpen,
            onOpenChange = onNavigatorOpenChange,
            navigatorContent = navigatorContent,
            modifier = Modifier.fillMaxSize(),
            content = scaffold,
        )
    }
}
