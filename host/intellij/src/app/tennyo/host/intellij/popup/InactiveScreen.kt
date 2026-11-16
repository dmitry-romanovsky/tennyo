package app.tennyo.host.intellij.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.intellij.DiscoveryStatus
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.PopupScreen
import app.tennyo.host.intellij.PopupState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.tooltipStyle

@Composable
internal fun InactivePopupScreen(
    state: PopupState,
    onStart: () -> Unit,
    onClose: () -> Unit,
    onDismissStartSessionError: () -> Unit,
    onRevokeRememberedDevice: (String) -> Unit,
    onVisibilitySelected: (HostVisibility) -> Unit,
    onDismissDiscoveryBlocked: () -> Unit,
    onResetDiscoveryTrust: () -> Unit,
    onSetRemoteAccess: (Boolean) -> Unit,
    onCopyAddress: (String) -> Boolean,
    onGetTailscale: () -> Unit,
    onExtendWindow: () -> Unit,
    connectionHelpExpanded: Boolean,
    onToggleConnectionHelp: () -> Unit,
    onCopyExecutablePath: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val window = state.pairingWindow
    PopupFrame(
        state = state,
        accent = popupScreenAccent(PopupScreen.Inactive, state),
        statusTitle = HostBundle.message(if (window != null) "popup.waiting.title" else "popup.start.title"),
        statusBody = HostBundle.message(if (window != null) "popup.waiting.body" else "popup.start.body"),
        primary = when {
            window != null -> PopupAction(
                text = HostBundle.message("popup.button.extend.window"),
                onClick = onExtendWindow,
            )

            else -> PopupAction(
                text = if (state.isStarting) {
                    HostBundle.message("popup.button.starting")
                } else {
                    HostBundle.message("popup.button.start.session")
                },
                enabled = !state.isStarting,
                onClick = onStart,
            )
        },
        secondary = PopupAction(text = HostBundle.message("popup.button.close"), onClick = onClose),
        modifier = modifier,
        waiting = window != null || state.isStarting,
    ) {
        state.error?.let { error ->
            PopupErrorMessage(
                message = error,
                onDismiss = onDismissStartSessionError,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        VisibilitySection(
            state = state,
            onVisibilitySelected = onVisibilitySelected,
            onDismissDiscoveryBlocked = onDismissDiscoveryBlocked,
            onResetDiscoveryTrust = onResetDiscoveryTrust,
            modifier = Modifier.fillMaxWidth(),
        )

        RemoteAccessSection(
            remoteAccess = state.remoteAccess,
            onSetRemoteAccess = onSetRemoteAccess,
            onCopyAddress = onCopyAddress,
            onGetTailscale = onGetTailscale,
            modifier = Modifier.fillMaxWidth(),
        )

        // "All" adds what the phone needs to pick this computer out; known devices wait until pairing mode closes.
        if (window != null) {
            PairingWindowDetails(
                window = window,
                connectionHelpExpanded = connectionHelpExpanded,
                onToggleConnectionHelp = onToggleConnectionHelp,
                onCopyExecutablePath = onCopyExecutablePath,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            KnownDevicesSection(
                devices = state.rememberedDevices,
                blocked = state.visibility == HostVisibility.NO_ONE,
                onForget = onRevokeRememberedDevice,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Design 2c: visibility is the one standing question, and it has exactly three answers. */
@Composable
private fun VisibilitySection(
    state: PopupState,
    onVisibilitySelected: (HostVisibility) -> Unit,
    onDismissDiscoveryBlocked: () -> Unit,
    onResetDiscoveryTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
        PopupSectionTitle(HostBundle.message("popup.visibility.title"))
        HostVisibility.entries.forEach { visibility ->
            VisibilityOptionRow(
                visibility = visibility,
                selected = visibility == state.visibility,
                onSelect = { onVisibilitySelected(visibility) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.discoveryServerStatus == DiscoveryStatus.Blocked) {
            Text(
                text = HostBundle.message("popup.auto.start.status.blocked"),
                color = popupWarningAccent(),
                fontWeight = FontWeight.Medium,
            )
        }
        if (!state.discoveryBlockDismissed && state.discoveryBlockedMessage != null) {
            PopupPanel(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = popupWarningBackground(),
                borderColor = popupWarningBorder(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
                    Text(
                        text = state.discoveryBlockedMessage,
                        color = popupWarningAccent(),
                        fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
                    ) {
                        PopupButton(
                            text = HostBundle.message("popup.auto.start.dismiss"),
                            icon = AllIconsKeys.Actions.Cancel,
                            modifier = Modifier.weight(1f),
                            style = PopupButtonStyle.Secondary,
                            onClick = onDismissDiscoveryBlocked,
                        )
                        PopupButton(
                            text = HostBundle.message("popup.auto.start.reset"),
                            icon = AllIconsKeys.General.Reset,
                            modifier = Modifier.weight(1f),
                            style = PopupButtonStyle.Secondary,
                            onClick = onResetDiscoveryTrust,
                        )
                    }
                }
            }
        }
    }
}

/** One of the three answers to who can see this computer; the selected one is the mode in force. */
@Composable
private fun VisibilityOptionRow(
    visibility: HostVisibility,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(JewelTheme.tooltipStyle.metrics.cornerSize))
            .background(if (isHovered) popupTextColor().copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelect, interactionSource = interactionSource)
            .padding(vertical = spacing.verticalComponentGap.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.verticalCommentBottomGap.dp)) {
            Text(text = visibility.title(), fontWeight = FontWeight.Medium)
            Text(
                text = visibility.body(),
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            )
        }
    }
}

private fun HostVisibility.title(): String = HostBundle.message(
    when (this) {
        HostVisibility.NO_ONE -> "popup.visibility.no.one"
        HostVisibility.KNOWN_DEVICES -> "popup.visibility.known"
        HostVisibility.ALL -> "popup.visibility.all"
    },
)

private fun HostVisibility.body(): String = HostBundle.message(
    when (this) {
        HostVisibility.NO_ONE -> "popup.visibility.no.one.body"
        HostVisibility.KNOWN_DEVICES -> "popup.visibility.known.body"
        HostVisibility.ALL -> "popup.visibility.all.body"
    },
)
