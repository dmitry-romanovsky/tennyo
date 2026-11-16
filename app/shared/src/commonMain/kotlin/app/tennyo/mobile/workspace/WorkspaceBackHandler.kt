package app.tennyo.mobile.workspace

import androidx.compose.runtime.Composable

@Composable
internal expect fun PlatformWorkspaceBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
