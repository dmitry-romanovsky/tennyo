package app.tennyo.mobile.feature.connect

import androidx.compose.runtime.Composable
internal class NotificationPermissionController(
    val status: NotificationPermissionStatus,
    val request: () -> Unit,
)

@Composable
internal expect fun rememberNotificationPermissionController(
    component: ConnectComponent,
): NotificationPermissionController
