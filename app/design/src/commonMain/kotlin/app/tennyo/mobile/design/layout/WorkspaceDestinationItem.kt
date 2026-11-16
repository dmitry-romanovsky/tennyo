package app.tennyo.mobile.design.layout

import androidx.compose.ui.graphics.vector.ImageVector

/** One destination in [WorkspaceNavigationStrip]: where the user works, not something they open over their work. */
data class WorkspaceDestinationItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    /** A count the destination wants noticed, such as changed files; `null` shows nothing. */
    val badge: Int? = null,
)
