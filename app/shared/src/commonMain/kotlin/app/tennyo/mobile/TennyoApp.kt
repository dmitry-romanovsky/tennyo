package app.tennyo.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.tennyo.mobile.design.editor.LocalEditorSoftWrap
import app.tennyo.mobile.design.layout.KeyboardVisibilityProvider
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.connect.ConnectScreen
import app.tennyo.mobile.feature.connect.HomeScreen
import app.tennyo.mobile.feature.local.CloneWorkspaceScreen
import app.tennyo.mobile.feature.local.CreateWorkspaceScreen
import app.tennyo.mobile.feature.local.GitSettingsPage
import app.tennyo.mobile.feature.local.LocalWorkspaceScreen
import app.tennyo.mobile.feature.settings.SettingsScreen
import app.tennyo.mobile.feature.terminal.TerminalHotkeysPage
import app.tennyo.mobile.root.RootComponent
import app.tennyo.mobile.root.rememberLocalHomeContent
import app.tennyo.mobile.ui.appVersionName
import app.tennyo.mobile.workspace.WorkspaceScreen
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation

@Composable
fun TennyoApp(
    rootComponent: RootComponent,
    modifier: Modifier = Modifier,
) {
    val appSettings by rootComponent.appSettings.collectAsState()
    TennyoTheme(
        themeMode = appSettings.themeMode,
        colorScheme = appSettings.colorScheme,
    ) {
        PlatformSystemBars(appSettings.themeMode)
        CompositionLocalProvider(LocalEditorSoftWrap provides appSettings.editorSoftWrap) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                KeyboardVisibilityProvider {
                    Children(
                        stack = rootComponent.childStack,
                        animation = rootStackAnimation(rootComponent),
                    ) { child ->
                        when (val instance = child.instance) {
                            is RootComponent.Child.Home -> HomeScreen(
                                component = instance.component,
                                local = instance.local?.let { rememberLocalHomeContent(it) },
                            )
                            is RootComponent.Child.Connect -> ConnectScreen(
                                component = instance.component,
                            )

                            is RootComponent.Child.Workspace -> WorkspaceScreen(instance.component)
                            is RootComponent.Child.Settings -> SettingsScreen(
                                component = instance.component,
                                appVersionName = appVersionName,
                                hotkeysPage = {
                                    val preferences = instance.terminalPreferences
                                    val keyBar by preferences.keyBar.collectAsState()
                                    TerminalHotkeysPage(
                                        keyBar = keyBar,
                                        onEdit = preferences::edit,
                                    )
                                },
                                gitPage = instance.git?.let { git -> { GitSettingsPage(git) } },
                            )

                            is RootComponent.Child.LocalWorkspace -> LocalWorkspaceScreen(instance.component)
                            is RootComponent.Child.CreateWorkspace -> CreateWorkspaceScreen(instance.component)
                            is RootComponent.Child.CloneWorkspace -> CloneWorkspaceScreen(instance.component)
                        }
                    }
                }
            }
        }
    }
}

/** Status and navigation bar appearance that follows the app theme where the platform lets the app draw it. */
@Composable
internal expect fun PlatformSystemBars(themeMode: AppThemeMode)

/** Android animates stack changes with its predictive back gesture; iOS keeps the plain stack. */
internal expect fun rootStackAnimation(rootComponent: RootComponent): StackAnimation<Any, RootComponent.Child>?
