package app.tennyo.host.intellij.popup

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.contract.Capability
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_MAX_ATTEMPTS
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.engine.panel.OverlayAddress
import app.tennyo.host.engine.panel.RemoteAccessPanel
import app.tennyo.host.engine.session.ConnectionRequestStatus
import app.tennyo.host.engine.transport.OverlayNetwork
import app.tennyo.host.intellij.ConnectionRequestState
import app.tennyo.host.intellij.DiscoveryStatus
import app.tennyo.host.intellij.PairingWindowState
import app.tennyo.host.intellij.PopupContent
import app.tennyo.host.intellij.PopupModel
import app.tennyo.host.intellij.PopupState
import app.tennyo.host.intellij.RememberedDeviceState
import app.tennyo.host.intellij.ShowPopupAction
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Clock

@Composable
@Preview
internal fun PopupPreview() {
    var selectedScenario by remember { mutableStateOf(PreviewPopupScenario.Inactive) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PopupPanel(
            modifier = Modifier.width(ShowPopupAction.POPUP_WIDTH.dp),
            backgroundColor = popupBackground(),
            borderColor = popupBorderColor(),
        ) {
            Text(
                text = "Preview state",
                fontWeight = FontWeight.SemiBold,
            )
            PreviewPopupScenario.entries.chunked(3).forEach { rowScenarios ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
                ) {
                    rowScenarios.forEach { scenario ->
                        PopupButton(
                            text = scenario.label,
                            icon = if (selectedScenario == scenario) {
                                AllIconsKeys.General.InspectionsOK
                            } else {
                                AllIconsKeys.General.ArrowRight
                            },
                            modifier = Modifier.weight(1f),
                            style = if (selectedScenario == scenario) {
                                PopupButtonStyle.Primary
                            } else {
                                PopupButtonStyle.Secondary
                            },
                            size = PopupButtonSize.Small,
                            onClick = { selectedScenario = scenario },
                        )
                    }
                    repeat(3 - rowScenarios.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        PopupContent(
            model = selectedScenario.model(),
            onClose = {},
            previewConfig = selectedScenario.previewConfig,
            modifier = Modifier.width(ShowPopupAction.POPUP_WIDTH.dp).border(1.dp, Color.Yellow.copy(alpha = 0.3f)),
        )
    }
}

internal data class PopupPreviewConfig(
    val connectionHelpExpanded: Boolean = false,
)

private enum class PreviewPopupScenario(
    val label: String,
    val model: () -> PreviewPopupModel,
    val previewConfig: PopupPreviewConfig = PopupPreviewConfig(),
) {
    Inactive(
        label = "Inactive",
        model = { PreviewPopupModel.inactive() },
    ),
    RemoteOff(
        label = "Remote off",
        model = { PreviewPopupModel.inactive(remoteAccess = remoteAccessPanel(enabled = false)) },
    ),
    RemoteOn(
        label = "Remote on",
        model = {
            PreviewPopupModel.inactive(
                remoteAccess = remoteAccessPanel(
                    enabled = true,
                    overlay = listOf(
                        OverlayAddress(OverlayNetwork.TAILNET_RANGE, "100.64.0.11"),
                        OverlayAddress(OverlayNetwork.ZEROTIER, "10.147.17.5"),
                    ),
                ),
            )
        },
    ),
    RemoteNoNetwork(
        label = "Remote, no network",
        model = { PreviewPopupModel.inactive(remoteAccess = remoteAccessPanel(enabled = true)) },
    ),
    Waiting(
        label = "Waiting",
        model = { PreviewPopupModel.waiting() },
    ),
    WaitingHelp(
        label = "Waiting help",
        model = { PreviewPopupModel.waiting() },
        previewConfig = PopupPreviewConfig(connectionHelpExpanded = true),
    ),
    Request(
        label = "Request",
        model = { PreviewPopupModel.request() },
    ),
    Pairing(
        label = "Pairing",
        model = { PreviewPopupModel.pairing() },
    ),
    WrongWords(
        label = "Wrong words",
        model = { PreviewPopupModel.wrongWords() },
    ),
    Connected(
        label = "Connected",
        model = { PreviewPopupModel.connected() },
    ),
}

private class PreviewPopupModel(
    override val state: PopupState,
) : PopupModel {
    override fun refresh() {}
    override fun startSession() {}
    override fun dismissStartSessionError() {}
    override fun copyExecutablePath() = true
    override fun extendPairingWindow() = Unit

    override fun endSession() {}
    override fun accept(requestId: String) {}
    override fun reject(requestId: String) {}
    override fun revokeRememberedDevice(pairId: String) {}
    override fun setVisibility(visibility: HostVisibility) = Unit
    override fun dismissDiscoveryBlockedMessage() {}
    override fun resetDiscoveryTrust() {}
    override fun setRemoteAccess(enabled: Boolean) {}
    override fun copyAddress(address: String) = true
    override fun openTailscaleDownload() {}

    companion object {
        fun inactive(remoteAccess: RemoteAccessPanel = remoteAccessPanel(enabled = false)): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    rememberedDevices = listOf(
                        RememberedDeviceState(
                            pairId = "pair-1",
                            deviceLabel = "Pixel 8 Android 15",
                            deviceName = "Pixel 8",
                            deviceOs = "Android",
                            deviceOsVersion = "15",
                            hoursLeft = 12,
                        ),
                    ),
                    discoveryEnabled = true,
                    visibility = HostVisibility.KNOWN_DEVICES,
                    discoveryServerStatus = DiscoveryStatus.Listening,
                    remoteAccess = remoteAccess,
                ),
            )

        /** Design 2b-2: the name and id the phone picks this computer by, counting down. */
        fun waiting(): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    sessionId = "session",
                    projectPath = "/tmp/spring-petclinic",
                    visibility = HostVisibility.ALL,
                    pairingWindow = PairingWindowState(
                        hostName = "Amber Harbour",
                        displayShortId = "abcd 1234 ef56",
                        address = "192.168.1.24:52814",
                        expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + PREVIEW_WINDOW_MILLIS,
                    ),
                ),
            )

        fun request(): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    sessionId = "session",
                    projectPath = "/tmp/spring-petclinic",
                    pendingRequests = listOf(previewRequest(ConnectionRequestStatus.PENDING)),
                ),
            )

        fun pairing(): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    sessionId = "session",
                    projectPath = "/tmp/spring-petclinic",
                    visibility = HostVisibility.ALL,
                    pendingRequests = listOf(previewRequest(ConnectionRequestStatus.AWAITING_PAIRING)),
                ),
            )

        /** Design 2b-5: the same words, one attempt fewer. */
        fun wrongWords(): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    sessionId = "session",
                    projectPath = "/tmp/spring-petclinic",
                    visibility = HostVisibility.ALL,
                    pairingAttemptsLeft = PAIRING_CONFIRMATION_MAX_ATTEMPTS - 1,
                    pendingRequests = listOf(previewRequest(ConnectionRequestStatus.AWAITING_PAIRING)),
                ),
            )

        fun connected(): PreviewPopupModel =
            PreviewPopupModel(
                state = PopupState(
                    projectName = "spring-petclinic",
                    sessionId = "session",
                    projectPath = "/tmp/spring-petclinic",
                    isConnected = true,
                    connectedDeviceName = "Pixel 8 Android 15",
                    connectedDeviceShortName = "Pixel 8",
                    connectedDeviceId = "device-key-hash",
                    capabilities = setOf(
                        Capability.FILES,
                        Capability.LAUNCH_CONFIGURATIONS,
                        Capability.TERMINAL,
                        Capability.GIT,
                    ),
                ),
            )

        private fun previewRequest(status: ConnectionRequestStatus): ConnectionRequestState =
            ConnectionRequestState(
                id = "android",
                clientName = "Pixel 8",
                clientVersion = "1.0",
                deviceFingerprint = "abcd1234ef567890",
                pairingWords = listOf("Golden", "Meadow", "Comet", "Zephyr"),
                status = status,
            )
    }
}

private const val PREVIEW_PORT = 63_343

private fun remoteAccessPanel(enabled: Boolean, overlay: List<OverlayAddress> = emptyList()) =
    RemoteAccessPanel(enabled = enabled, available = true, port = PREVIEW_PORT, overlay = overlay)

/** Long enough that the countdown in a preview reads like a live one. */
private const val PREVIEW_WINDOW_MILLIS = 108_000L
