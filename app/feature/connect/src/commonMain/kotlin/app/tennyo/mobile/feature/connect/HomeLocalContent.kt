package app.tennyo.mobile.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What a platform with on-device workspaces adds to Home: its actions beside the connect button and its list of recent
 * workspaces. [hasWorkspaces] picks the layout: a list beside or below the actions, or onboarding when there is none.
 */
class HomeLocalContent(
    val hasWorkspaces: Boolean,
    val actions: @Composable (Modifier) -> Unit,
    val workspaces: @Composable (Modifier) -> Unit,
)
