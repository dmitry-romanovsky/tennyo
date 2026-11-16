package app.tennyo.host.intellij

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tennyo.host.intellij.popup.ConnectedPopupScreen
import app.tennyo.host.intellij.popup.ConnectionPairingPopupScreen
import app.tennyo.host.intellij.popup.ConnectionRequestPopupScreen
import app.tennyo.host.intellij.popup.InactivePopupScreen
import app.tennyo.host.intellij.popup.PopupPreviewConfig
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun PopupContent(
    model: PopupModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    previewConfig: PopupPreviewConfig? = null,
) {
    val state = model.state
    // Folded again whenever pairing mode opens or closes, so the help never greets a fresh window already open.
    var connectionHelpExpanded by remember(state.pairingWindow != null) {
        mutableStateOf(previewConfig?.connectionHelpExpanded == true && state.pairingWindow != null)
    }

    LaunchedEffect(model) {
        while (true) {
            delay(1.seconds)
            model.refresh()
        }
    }

    val screenModifier = modifier.widthIn(min = ShowPopupAction.POPUP_WIDTH.dp).fillMaxWidth()
    when (val screen = state.screen) {
        PopupScreen.Inactive -> InactivePopupScreen(
            state = state,
            onStart = model::startSession,
            onClose = onClose,
            onDismissStartSessionError = model::dismissStartSessionError,
            onRevokeRememberedDevice = model::revokeRememberedDevice,
            onVisibilitySelected = model::setVisibility,
            onDismissDiscoveryBlocked = model::dismissDiscoveryBlockedMessage,
            onResetDiscoveryTrust = model::resetDiscoveryTrust,
            onSetRemoteAccess = model::setRemoteAccess,
            onCopyAddress = model::copyAddress,
            onGetTailscale = model::openTailscaleDownload,
            onExtendWindow = model::extendPairingWindow,
            connectionHelpExpanded = connectionHelpExpanded,
            onToggleConnectionHelp = { connectionHelpExpanded = !connectionHelpExpanded },
            onCopyExecutablePath = model::copyExecutablePath,
            modifier = screenModifier,
        )

        is PopupScreen.ConnectionRequest -> ConnectionRequestPopupScreen(
            state = state,
            request = screen.request,
            onAccept = model::accept,
            onReject = model::reject,
            onEnd = model::endSession,
            modifier = screenModifier,
        )

        is PopupScreen.ConnectionPairing -> ConnectionPairingPopupScreen(
            state = state,
            request = screen.request,
            onEnd = model::endSession,
            modifier = screenModifier,
        )

        PopupScreen.Connected -> ConnectedPopupScreen(
            state = state,
            onEnd = model::endSession,
            modifier = screenModifier,
        )
    }
}
