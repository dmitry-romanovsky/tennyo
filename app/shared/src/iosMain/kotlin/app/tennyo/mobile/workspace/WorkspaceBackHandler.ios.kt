package app.tennyo.mobile.workspace

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformWorkspaceBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
