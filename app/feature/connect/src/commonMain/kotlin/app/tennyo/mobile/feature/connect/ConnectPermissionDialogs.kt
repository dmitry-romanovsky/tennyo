package app.tennyo.mobile.feature.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_card_description
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_card_title
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_continue
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_dialog_description
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_dialog_title
import app.tennyo.mobile.feature.connect.generated.resources.notification_permission_not_now
import org.jetbrains.compose.resources.stringResource



@Composable
internal fun NotificationPermissionDialog(component: ConnectComponent, modifier: Modifier = Modifier) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            component.onIntent(ConnectIntent.NotificationPermissionRequestDismissed)
        },
        title = { Text(text = stringResource(Res.string.notification_permission_dialog_title)) },
        text = { Text(text = stringResource(Res.string.notification_permission_dialog_description)) },
        confirmButton = {
            TennyoButton(
                label = stringResource(Res.string.notification_permission_continue),
                style = TennyoButtonStyle.Text,
                onClick = {
                    component.onIntent(ConnectIntent.NotificationPermissionRequestConfirmed)
                },
            )
        },
        dismissButton = {
            TennyoButton(
                label = stringResource(Res.string.notification_permission_not_now),
                style = TennyoButtonStyle.Text,
                onClick = {
                    component.onIntent(ConnectIntent.NotificationPermissionRequestDismissed)
                },
            )
        },
    )
}
