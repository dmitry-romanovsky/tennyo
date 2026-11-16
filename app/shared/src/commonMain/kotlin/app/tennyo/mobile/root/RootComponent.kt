package app.tennyo.mobile.root

import app.tennyo.mobile.feature.connect.ConnectComponent
import app.tennyo.mobile.feature.connect.HomeComponent
import app.tennyo.mobile.feature.local.CloneWorkspaceComponent
import app.tennyo.mobile.feature.local.CreateWorkspaceComponent
import app.tennyo.mobile.feature.local.GitSettingsComponent
import app.tennyo.mobile.feature.local.LocalHomeComponent
import app.tennyo.mobile.feature.local.LocalWorkspaceComponent
import app.tennyo.mobile.feature.settings.AppSettingsState
import app.tennyo.mobile.feature.settings.SettingsComponent
import app.tennyo.mobile.feature.terminal.TerminalPreferences
import app.tennyo.mobile.workspace.WorkspaceComponent
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandler
import kotlinx.coroutines.flow.StateFlow

interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>
    val backHandler: BackHandler
    val appSettings: StateFlow<AppSettingsState>

    fun onBack()

    sealed interface Child {
        /** Home, with the on-device workspaces section where the platform has one. */
        class Home(val component: HomeComponent, val local: LocalHomeComponent?) : Child
        class Connect(val component: ConnectComponent) : Child
        class Workspace(val component: WorkspaceComponent) : Child
        /** Settings, with the terminal preferences its hotkeys page edits and, where it exists, the Git page. */
        class Settings(
            val component: SettingsComponent,
            val terminalPreferences: TerminalPreferences,
            val git: GitSettingsComponent?,
        ) : Child

        class LocalWorkspace(val component: LocalWorkspaceComponent) : Child
        class CreateWorkspace(val component: CreateWorkspaceComponent) : Child
        class CloneWorkspace(val component: CloneWorkspaceComponent) : Child
    }
}
