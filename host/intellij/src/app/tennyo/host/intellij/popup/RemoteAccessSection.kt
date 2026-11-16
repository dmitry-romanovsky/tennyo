package app.tennyo.host.intellij.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tennyo.host.engine.panel.OverlayAddress
import app.tennyo.host.engine.panel.RemoteAccessPanel
import app.tennyo.host.engine.transport.OverlayNetwork
import app.tennyo.host.intellij.HostBundle
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Whether a phone away from home may reach this computer, and where: the private-network addresses it would dial, or
 * what to install when there are none. The switch is disabled while nobody may discover this computer at all.
 */
@Composable
internal fun RemoteAccessSection(
    remoteAccess: RemoteAccessPanel,
    onSetRemoteAccess: (Boolean) -> Unit,
    onCopyAddress: (String) -> Boolean,
    onGetTailscale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
        PopupSectionTitle(HostBundle.message("popup.remote.title"))
        CheckboxRow(
            text = HostBundle.message("popup.remote.switch"),
            checked = remoteAccess.enabled,
            enabled = remoteAccess.available || remoteAccess.enabled,
            onCheckedChange = onSetRemoteAccess,
        )
        if (!remoteAccess.available && !remoteAccess.enabled) {
            PopupNote(HostBundle.message("popup.remote.unavailable"))
        }
        if (!remoteAccess.enabled) return@Column
        if (remoteAccess.overlay.isEmpty()) {
            PopupNote(HostBundle.message("popup.remote.none"))
            PopupButton(
                text = HostBundle.message("popup.remote.get.tailscale"),
                icon = AllIconsKeys.General.Web,
                size = PopupButtonSize.Small,
                onClick = onGetTailscale,
            )
            return@Column
        }
        remoteAccess.overlay.forEach { overlay ->
            key(overlay.address) {
                OverlayAddressRow(
                    overlay = overlay,
                    port = remoteAccess.port,
                    onCopy = onCopyAddress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PopupNote(HostBundle.message("popup.remote.learn"))
    }
}

@Composable
private fun OverlayAddressRow(
    overlay: OverlayAddress,
    port: Int?,
    onCopy: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val endpoint = listOfNotNull(overlay.address, port?.toString()).joinToString(":")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = HostBundle.message(
                "popup.remote.address",
                overlay.network.title(),
                overlay.address,
                port?.toString().orEmpty(),
            ),
            modifier = Modifier.weight(1f),
        )
        PopupCopyButton(text = HostBundle.message("popup.remote.copy"), onClick = { onCopy(endpoint) })
    }
}

private fun OverlayNetwork.title(): String = HostBundle.message(
    when (this) {
        OverlayNetwork.TAILNET_RANGE -> "popup.remote.network.tailscale"
        OverlayNetwork.ZEROTIER -> "popup.remote.network.zerotier"
        OverlayNetwork.WIREGUARD -> "popup.remote.network.wireguard"
    },
)
