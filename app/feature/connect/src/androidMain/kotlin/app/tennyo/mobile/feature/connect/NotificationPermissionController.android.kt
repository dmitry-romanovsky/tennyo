package app.tennyo.mobile.feature.connect

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
@Composable
internal actual fun rememberNotificationPermissionController(
    component: ConnectComponent,
): NotificationPermissionController {
    val context = LocalContext.current
    var status by remember(context) {
        mutableStateOf(context.notificationPermissionStatus())
    }
    var pendingRequest by remember(context) {
        mutableStateOf<PendingNotificationPermissionRequest?>(null)
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        status = context.notificationPermissionStatus()
        component.onIntent(ConnectIntent.NotificationPermissionRequestResult(status.isGranted))
    }
    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        status = context.notificationPermissionStatus().copy(isGranted = isGranted)
        val resultAction = notificationPermissionResultAction(
            status = status,
            rationaleBeforeRequest = pendingRequest?.rationaleBeforeRequest == true,
            rationaleAfterRequest = context.shouldShowNotificationPermissionRationale(),
        )
        pendingRequest = null
        when (resultAction) {
            NotificationPermissionResultAction.ReportCurrentStatus -> {
                component.onIntent(ConnectIntent.NotificationPermissionRequestResult(status.isGranted))
            }

            NotificationPermissionResultAction.OpenSettings -> {
                settingsLauncher.launch(context.notificationSettingsIntent())
            }
        }
    }

    LaunchedEffect(context) {
        status = context.notificationPermissionStatus()
    }

    return remember(component, context, requestLauncher, settingsLauncher, status) {
        NotificationPermissionController(
            status = status,
            request = {
                val currentStatus = context.notificationPermissionStatus()
                status = currentStatus
                when (notificationPermissionAction(currentStatus)) {
                    NotificationPermissionAction.ReportCurrentStatus -> {
                        component.onIntent(
                            ConnectIntent.NotificationPermissionRequestResult(currentStatus.isGranted)
                        )
                    }

                    NotificationPermissionAction.RequestRuntimePermission -> {
                        pendingRequest = PendingNotificationPermissionRequest(
                            rationaleBeforeRequest = context.shouldShowNotificationPermissionRationale(),
                        )
                        requestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
        )
    }
}

internal enum class NotificationPermissionAction {
    ReportCurrentStatus,
    RequestRuntimePermission,
}

internal data class PendingNotificationPermissionRequest(
    val rationaleBeforeRequest: Boolean,
)

internal enum class NotificationPermissionResultAction {
    ReportCurrentStatus,
    OpenSettings,
}

internal fun notificationPermissionAction(
    status: NotificationPermissionStatus,
): NotificationPermissionAction =
    when {
        status.isGranted -> NotificationPermissionAction.ReportCurrentStatus
        else -> NotificationPermissionAction.RequestRuntimePermission
    }

internal fun notificationPermissionResultAction(
    status: NotificationPermissionStatus,
    rationaleBeforeRequest: Boolean,
    rationaleAfterRequest: Boolean,
): NotificationPermissionResultAction =
    if (
        !status.isGranted &&
        !rationaleBeforeRequest &&
        !rationaleAfterRequest
    ) {
        NotificationPermissionResultAction.OpenSettings
    } else {
        NotificationPermissionResultAction.ReportCurrentStatus
    }

private fun Context.notificationPermissionStatus(): NotificationPermissionStatus {
    val isGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    return NotificationPermissionStatus(isGranted = isGranted)
}

private fun Context.shouldShowNotificationPermissionRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Context.notificationSettingsIntent(): Intent {
    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
}
