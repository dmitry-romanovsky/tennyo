package app.tennyo.mobile.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
@Composable
internal actual fun rememberNotificationPermissionController(
    component: ConnectComponent,
): NotificationPermissionController =
    remember(component) {
        NotificationPermissionController(
            status = NotificationPermissionStatus(
                isGranted = true,
            ),
            request = {
                component.onIntent(ConnectIntent.NotificationPermissionRequestResult(true))
            },
        )
    }
