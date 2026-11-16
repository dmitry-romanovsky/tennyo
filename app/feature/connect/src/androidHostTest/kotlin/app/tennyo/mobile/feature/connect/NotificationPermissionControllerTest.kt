package app.tennyo.mobile.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionControllerTest {
    @Test
    fun deniedAndroidRuntimePermissionRequestsSystemDialog() {
        val action = notificationPermissionAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
        )

        assertEquals(NotificationPermissionAction.RequestRuntimePermission, action)
    }

    @Test
    fun settingsRevokedAndroidRuntimePermissionStillRequestsSystemDialog() {
        val action = notificationPermissionAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
        )

        assertEquals(NotificationPermissionAction.RequestRuntimePermission, action)
    }

    @Test
    fun deniedResultWithoutRationaleBeforeOrAfterRequestOpensSettings() {
        val action = notificationPermissionAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
        )

        val resultAction = notificationPermissionResultAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
            rationaleBeforeRequest = false,
            rationaleAfterRequest = false,
        )

        assertEquals(NotificationPermissionAction.RequestRuntimePermission, action)
        assertEquals(NotificationPermissionResultAction.OpenSettings, resultAction)
    }

    @Test
    fun deniedResultWithRationaleBeforeOrAfterRequestReportsStatus() {
        val afterShown = notificationPermissionResultAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
            rationaleBeforeRequest = false,
            rationaleAfterRequest = true,
        )
        val beforeShown = notificationPermissionResultAction(
            status = NotificationPermissionStatus(
                isGranted = false,
            ),
            rationaleBeforeRequest = true,
            rationaleAfterRequest = false,
        )

        assertEquals(NotificationPermissionResultAction.ReportCurrentStatus, afterShown)
        assertEquals(NotificationPermissionResultAction.ReportCurrentStatus, beforeShown)
    }

    @Test
    fun grantedPermissionOnlyReportsStatus() {
        val granted = notificationPermissionAction(
            status = NotificationPermissionStatus(isGranted = true),
        )

        assertEquals(NotificationPermissionAction.ReportCurrentStatus, granted)
    }
}
