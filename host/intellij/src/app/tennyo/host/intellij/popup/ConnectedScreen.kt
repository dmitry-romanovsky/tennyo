package app.tennyo.host.intellij.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.contract.Capability
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.PopupScreen
import app.tennyo.host.intellij.PopupState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus

@Composable
internal fun ConnectedPopupScreen(
    state: PopupState,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceName = state.connectedDeviceShortName ?: state.connectedDeviceName
        ?: HostBundle.message("popup.connected.device.default")

    PopupFrame(
        state = state,
        accent = popupScreenAccent(PopupScreen.Connected, state),
        statusTitle = HostBundle.message("popup.connected.title"),
        statusBody = HostBundle.message("popup.connected.body"),
        primary = null,
        secondary = PopupAction(
            text = stoppingButtonText(state),
            enabled = !state.isStopping,
            onClick = onEnd,
        ),
        modifier = modifier,
    ) {
        ConnectedDeviceCard(
            deviceName = deviceName,
            uniqueId = state.connectedDeviceId,
            modifier = Modifier.fillMaxWidth(),
        )
        // What the phone can actually reach from here, so "connected" is not the whole answer.
        CapabilityChips(capabilities = state.capabilities, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectedDeviceCard(
    deviceName: String,
    uniqueId: String?,
    modifier: Modifier = Modifier,
) {
    PopupPanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceAvatar(
                deviceName = deviceName,
                container = popupPalette().primaryContainer,
                onContainer = popupPalette().onPrimaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.verticalCommentBottomGap.dp),
            ) {
                Text(
                    text = deviceName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (uniqueId != null) {
                    Text(
                        text = uniqueId,
                        color = popupMutedTextColor(),
                        fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(popupReadyAccent(), CircleShape),
            )
        }
    }
}

internal fun stoppingButtonText(state: PopupState): String =
    if (state.isStopping) {
        HostBundle.message("popup.button.stopping")
    } else {
        HostBundle.message("popup.button.end.session")
    }

/** What this session serves, in the order the phone offers it. */
@Composable
private fun CapabilityChips(capabilities: Set<Capability>, modifier: Modifier = Modifier) {
    if (capabilities.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Capability.entries.filter { it in capabilities }.forEach { capability ->
            Text(
                text = capability.chipLabel(),
                modifier = Modifier
                    .background(popupPalette().surfaceContainerHigh, popupShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
            )
        }
    }
}

private fun Capability.chipLabel(): String = HostBundle.message(
    when (this) {
        Capability.FILES -> "popup.capability.files"
        Capability.LAUNCH_CONFIGURATIONS -> "popup.capability.launch"
        Capability.TERMINAL -> "popup.capability.terminal"
        Capability.GIT -> "popup.capability.git"
        Capability.CHAT -> "popup.capability.chat"
    },
)
