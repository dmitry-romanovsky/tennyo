package app.tennyo.mobile.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.tennyo.mobile.feature.connect.HomeLocalContent
import app.tennyo.mobile.feature.local.LocalHomeComponent
import app.tennyo.mobile.feature.local.LocalWorkspaceActions
import app.tennyo.mobile.feature.local.LocalWorkspaceList

/** Fills Home's local slot with the on-device workspace actions and list. */
@Composable
internal fun rememberLocalHomeContent(component: LocalHomeComponent): HomeLocalContent {
    val state by component.state.collectAsState()
    return HomeLocalContent(
        hasWorkspaces = state.workspaces.isNotEmpty(),
        actions = { modifier -> LocalWorkspaceActions(onIntent = component::onIntent, modifier = modifier) },
        workspaces = { modifier ->
            LocalWorkspaceList(workspaces = state.workspaces, onIntent = component::onIntent, modifier = modifier)
        },
    )
}
