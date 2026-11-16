package app.tennyo.host.intellij

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tennyo.contract.Capability
import app.tennyo.crypto.HostShortId
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_MAX_ATTEMPTS
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.engine.panel.RemoteAccessPanel
import app.tennyo.host.engine.session.ConnectionRequest
import app.tennyo.host.engine.session.ConnectionRequestStatus
import app.tennyo.host.engine.session.closePairingWindow
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

@Stable
internal interface PopupModel {
    val state: PopupState

    fun refresh()
    fun startSession()
    fun dismissStartSessionError()
    fun copyExecutablePath(): Boolean
    /** Restarts the open pairing window's two minutes, keeping its name, id and token. */
    fun extendPairingWindow()

    fun endSession()
    fun accept(requestId: String)
    fun reject(requestId: String)
    fun revokeRememberedDevice(pairId: String)
    /** Sets who may reach this computer; see [HostVisibility]. */
    fun setVisibility(visibility: HostVisibility)
    fun dismissDiscoveryBlockedMessage()
    fun resetDiscoveryTrust()
    fun setRemoteAccess(enabled: Boolean)

    /** Puts [address] on the clipboard; `false` when it could not be. */
    fun copyAddress(address: String): Boolean

    fun openTailscaleDownload()
}

internal class RealPopupModel(
    private val project: Project,
    private val onStateChanged: () -> Unit,
) : PopupModel {
    private val service: HostSessionService = project.service()
    private val panel = ideHostPanelModel(service)
    private var starting by mutableStateOf(false)
    private var stopping by mutableStateOf(false)
    private var rememberedDevicesCache: List<RememberedDeviceState> = emptyList()
    private var revokingRememberedDeviceIds by mutableStateOf<Set<String>>(emptySet())
    private var revokedRememberedDeviceIds by mutableStateOf<Set<String>>(emptySet())
    private var rememberedDevicesLoadedAtEpochMillis: Long = 0L
    private var rememberedDevicesRefreshInFlight = false
    private var rememberedDevicesRefreshQueued = false
    private var discoveryTransition by mutableStateOf(DiscoveryTransition.None)
    private var sessionStartError by mutableStateOf<String?>(null)

    override var state: PopupState by mutableStateOf(readState())
        private set

    override fun refresh() {
        state = readState()
    }

    override fun startSession() {
        // A live session is no reason to bail: "All" reuses it and opens a window over it.
        val busy = starting || stopping || service.isStartingSession
        if (busy || service.host.pairingWindow.current() != null) return
        sessionStartError = null
        starting = true
        refresh()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service.openPairingWindow() }
                .onSuccess {
                    ApplicationManager.getApplication().invokeLater {
                        starting = false
                        refresh()
                        onStateChanged()
                    }
                }
                .onFailure { throwable ->
                    ApplicationManager.getApplication().invokeLater {
                        starting = false
                        sessionStartError = throwable.message
                            ?: HostBundle.message("popup.error.start.session")
                        refresh()
                        onStateChanged()
                    }
                }
        }
    }

    override fun dismissStartSessionError() {
        if (sessionStartError == null) return
        sessionStartError = null
        refresh()
        onStateChanged()
    }

    override fun copyExecutablePath(): Boolean {
        val path = currentExecutablePath().takeIf { it.isNotBlank() } ?: return false
        copyDeepLinkToClipboard(path)
        return true
    }

    override fun extendPairingWindow() {
        if (service.host.pairingWindow.extend() == null) return
        refresh()
        onStateChanged()
    }

    override fun endSession() {
        val sessionId = state.sessionId ?: return
        if (stopping) return
        stopping = true
        refresh()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                service.stopSession(sessionId, HostBundle.message("popup.stop.reason.ide"))
            }.onFailure { throwable ->
                LOG.warn("Unable to stop Tennyo Companion session $sessionId", throwable)
            }
            ApplicationManager.getApplication().invokeLater {
                stopping = false
                refresh()
                onStateChanged()
            }
        }
    }

    override fun accept(requestId: String) {
        val sessionId = state.sessionId ?: return
        service.acceptConnectionRequest(sessionId, requestId)
        refresh()
        onStateChanged()
    }

    override fun reject(requestId: String) {
        val sessionId = state.sessionId ?: return
        service.rejectConnectionRequest(sessionId, requestId)
        refresh()
        onStateChanged()
    }

    override fun revokeRememberedDevice(pairId: String) {
        if (pairId in revokingRememberedDeviceIds) return

        revokingRememberedDeviceIds = revokingRememberedDeviceIds + pairId
        refresh()
        onStateChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val revokeResult = runCatching { service.revokeRememberedPair(pairId) }
                .onFailure { LOG.warn("Unable to revoke remembered Tennyo device $pairId", it) }
            ApplicationManager.getApplication().invokeLater {
                revokingRememberedDeviceIds = revokingRememberedDeviceIds - pairId
                if (revokeResult.isSuccess) {
                    revokedRememberedDeviceIds = revokedRememberedDeviceIds + pairId
                }
                requestRememberedDevicesRefresh(force = true)
                refresh()
                onStateChanged()
            }
        }
    }

    override fun setVisibility(visibility: HostVisibility) {
        if (discoveryTransition != DiscoveryTransition.None) return
        if (visibility == HostVisibility.ALL) {
            startSession()
            return
        }
        // No one and Known devices differ only in discovery; either way no window - and no session - stays open.
        service.forgetModeBeforePairing()
        service.host.closePairingWindow()
        val shouldEnableDiscovery = visibility == HostVisibility.KNOWN_DEVICES
        if (shouldEnableDiscovery == service.isDiscoveryEnabled()) {
            refresh()
            onStateChanged()
            return
        }
        discoveryTransition = if (shouldEnableDiscovery) {
            DiscoveryTransition.Enabling
        } else {
            DiscoveryTransition.Disabling
        }
        refresh()
        onStateChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service.setDiscoveryEnabled(shouldEnableDiscovery) }
                .onFailure { LOG.warn("Unable to update Tennyo Companion discovery state", it) }
            ApplicationManager.getApplication().invokeLater {
                discoveryTransition = DiscoveryTransition.None
                refresh()
                onStateChanged()
            }
        }
    }

    override fun dismissDiscoveryBlockedMessage() {
        service.dismissDiscoveryBlockedBanner()
        refresh()
        onStateChanged()
    }

    override fun resetDiscoveryTrust() {
        if (discoveryTransition != DiscoveryTransition.None) return
        discoveryTransition = DiscoveryTransition.Disabling
        refresh()
        onStateChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service.resetDiscoveryTrust() }
                .onFailure { LOG.warn("Unable to reset Tennyo Companion discovery trust", it) }
            ApplicationManager.getApplication().invokeLater {
                discoveryTransition = DiscoveryTransition.None
                refresh()
                onStateChanged()
            }
        }
    }

    override fun setRemoteAccess(enabled: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service.setRemoteAccess(enabled) }
                .onFailure { LOG.warn("Unable to update Tennyo Companion remote access", it) }
            ApplicationManager.getApplication().invokeLater {
                refresh()
                onStateChanged()
            }
        }
    }

    override fun copyAddress(address: String): Boolean {
        copyDeepLinkToClipboard(address)
        return true
    }

    override fun openTailscaleDownload() = BrowserUtil.browse(TAILSCALE_DOWNLOAD_URL)

    /** The same three-way answer the shared panel state carries, read from the two things that decide it. */
    private fun currentVisibility(): HostVisibility = when {
        service.host.pairingWindow.current() != null -> HostVisibility.ALL
        service.isDiscoveryEnabled() -> HostVisibility.KNOWN_DEVICES
        else -> HostVisibility.NO_ONE
    }

    private fun readState(): PopupState {
        val session = service.activeSession()
        if (session == null) {
            return PopupState(
                projectName = project.name,
                visibility = currentVisibility(),
                isStarting = starting || service.isStartingSession,
                isStopping = stopping,
                rememberedDevices = refreshRememberedDevices(),
                discoveryEnabled = service.isDiscoveryEnabled(),
                discoveryBlockedMessage = service.discoveryBlockedMessage(),
                discoveryBlockDismissed = service.isDiscoveryBlockedBannerDismissed(),
                discoveryTransition = discoveryTransition,
                remoteAccess = panel.remoteAccess,
                discoveryServerStatus = when {
                    service.discoveryBlockedMessage() != null -> DiscoveryStatus.Blocked
                    service.isDiscoveryEnabled() -> DiscoveryStatus.Listening
                    else -> DiscoveryStatus.Disabled
                },
                error = sessionStartError,
            )
        }

        invalidateRememberedDevices()
        val sessionId = session.artifact.sessionId
        val pendingRequests = service.pendingConnectionRequests(sessionId)
        val isConnected = service.isClientConnected(sessionId)
        return PopupState(
            projectName = project.name,
            sessionId = sessionId,
            visibility = currentVisibility(),
            pairingAttemptsLeft = session.auth.pairingAttemptsLeft,
            capabilities = session.artifact.capabilities,
            projectPath = session.artifact.projectPath,
            pairingWindow = service.host.pairingWindow.current()?.let { window ->
                PairingWindowState(
                    hostName = window.hostName,
                    displayShortId = HostShortId.display(window.hostShortId),
                    expiresAtEpochMillis = window.expiresAtEpochMillis,
                    address = linkAddress(session.artifact.endpoint),
                )
            },
            pendingRequests = pendingRequests.map {
                ConnectionRequestState(
                    id = it.id,
                    clientName = it.clientName,
                    clientVersion = it.clientVersion,
                    deviceFingerprint = it.deviceFingerprint,
                    pairingWords = it.pairingWords,
                    status = it.status,
                )
            },
            isConnected = isConnected,
            connectedDeviceName = service.connectedDeviceName(sessionId),
            connectedDeviceShortName = service.connectedDeviceName(sessionId)?.let { parseDeviceOsInfo(it).first },
            connectedDeviceId = service.connectedDeviceId(sessionId),
            isStopping = stopping,
            remoteAccess = panel.remoteAccess,
            error = sessionStartError,
        )
    }

    private fun refreshRememberedDevices(force: Boolean = false): List<RememberedDeviceState> {
        val now = System.currentTimeMillis()
        if (force || now - rememberedDevicesLoadedAtEpochMillis >= REMEMBERED_DEVICES_REFRESH_INTERVAL_MILLIS) {
            requestRememberedDevicesRefresh(force = force)
        }
        return rememberedDevicesCache.filterNot { device ->
            device.pairId in revokingRememberedDeviceIds || device.pairId in revokedRememberedDeviceIds
        }
    }

    private fun invalidateRememberedDevices() {
        rememberedDevicesLoadedAtEpochMillis = 0L
    }

    private fun requestRememberedDevicesRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - rememberedDevicesLoadedAtEpochMillis < REMEMBERED_DEVICES_REFRESH_INTERVAL_MILLIS) return
        if (rememberedDevicesRefreshInFlight) {
            rememberedDevicesRefreshQueued = rememberedDevicesRefreshQueued || force
            return
        }
        rememberedDevicesRefreshInFlight = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val refreshedDevices = runCatching { service.rememberedPairs().map(::rememberedDeviceState) }
            ApplicationManager.getApplication().invokeLater {
                rememberedDevicesRefreshInFlight = false
                refreshedDevices
                    .onSuccess { devices ->
                        rememberedDevicesCache = devices
                        rememberedDevicesLoadedAtEpochMillis = System.currentTimeMillis()
                        state = readState()
                        onStateChanged()
                    }
                    .onFailure { LOG.warn("Unable to load Tennyo remembered devices", it) }

                if (rememberedDevicesRefreshQueued) {
                    rememberedDevicesRefreshQueued = false
                    requestRememberedDevicesRefresh(force = true)
                }
            }
        }
    }

    private fun copyDeepLinkToClipboard(link: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(link))
    }

    private fun rememberedDeviceState(summary: RememberedPairSummary): RememberedDeviceState {
        val hoursLeft = ((summary.expiresAtEpochMillis - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0L)
        val (name, osInfo) = parseDeviceOsInfo(summary.deviceLabel)
        return RememberedDeviceState(
            pairId = summary.pairId,
            deviceLabel = summary.deviceLabel,
            deviceName = name,
            deviceOs = osInfo.first,
            deviceOsVersion = osInfo.second,
            hoursLeft = hoursLeft,
            companionId = summary.companionId,
            lastUsedAtEpochMillis = summary.lastUsedAtEpochMillis,
        )
    }

    private companion object {
        private const val REMEMBERED_DEVICES_REFRESH_INTERVAL_MILLIS = 30_000L
        private const val TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download"
        private val LOG = Logger.getInstance(RealPopupModel::class.java)
    }
}

/** What the waiting screen shows while this computer is open to devices it has never met (design 2b-2). */
internal data class PairingWindowState(
    val hostName: String,
    /** Grouped for reading, as the phone shows it. */
    val displayShortId: String,
    val expiresAtEpochMillis: Long,
    /** `host:port` the phone reaches this computer at, for checking it is on the same network. */
    val address: String? = null,
)

internal data class PopupState(
    val projectName: String,
    val sessionId: String? = null,
    val projectPath: String? = null,
    /** The open pairing window, if there is one: the name and id the phone picks this computer by. */
    val pairingWindow: PairingWindowState? = null,
    /** Who may reach this computer — the popup's one standing question (design 2c). */
    val visibility: HostVisibility = HostVisibility.KNOWN_DEVICES,
    /** Pairing attempts left, as the verifier counts them; the words card shows the budget running down. */
    val pairingAttemptsLeft: Int = PAIRING_CONFIRMATION_MAX_ATTEMPTS,
    /** What this session serves, for the connected card's chips (design 2b-6). */
    val capabilities: Set<Capability> = emptySet(),
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val pendingRequests: List<ConnectionRequestState> = emptyList(),
    val isConnected: Boolean = false,
    val connectedDeviceName: String? = null,
    val connectedDeviceShortName: String? = null,
    val connectedDeviceId: String? = null,
    val rememberedDevices: List<RememberedDeviceState> = emptyList(),
    val discoveryEnabled: Boolean = false,
    val discoveryBlockedMessage: String? = null,
    val discoveryBlockDismissed: Boolean = false,
    val discoveryTransition: DiscoveryTransition = DiscoveryTransition.None,
    val discoveryServerStatus: DiscoveryStatus = DiscoveryStatus.Disabled,
    val remoteAccess: RemoteAccessPanel = RemoteAccessPanel(
        enabled = false,
        available = false,
        port = null,
        overlay = emptyList(),
    ),
    val screen: PopupScreen = popupScreen(
        sessionId = sessionId,
        pendingRequests = pendingRequests,
        isConnected = isConnected,
    ),
    val error: String? = null,
) {
    val hasActiveSession: Boolean get() = sessionId != null
}

internal enum class DiscoveryStatus {
    Disabled,
    Listening,
    Blocked,
}

internal enum class DiscoveryTransition {
    None,
    Enabling,
    Disabling,
}

/** What the popup shows, with the data only that screen needs. */
internal sealed interface PopupScreen {
    /** The main screen: the mode, and — while "All" holds a pairing window open — what the phone picks it by. */
    data object Inactive : PopupScreen

    /** A device asked to connect and waits for the user to accept or reject [request]. */
    data class ConnectionRequest(val request: ConnectionRequestState) : PopupScreen

    /** The user accepted [request]; the device shows the pairing words to confirm. */
    data class ConnectionPairing(val request: ConnectionRequestState) : PopupScreen

    data object Connected : PopupScreen
}

internal data class RememberedDeviceState(
    val pairId: String,
    val deviceLabel: String,
    val deviceName: String,
    val deviceOs: String? = null,
    val deviceOsVersion: String? = null,
    val hoursLeft: Long,
    val companionId: String? = null,
    /** When the phone last connected; `0` when this computer never recorded it. */
    val lastUsedAtEpochMillis: Long = 0L,
)

internal val deviceOsPattern = Regex("\\s+(Android|iOS)\\s+(\\S+)$")

internal fun parseDeviceOsInfo(deviceLabel: String): Pair<String, Pair<String?, String?>> {
    val label = deviceLabel.trim()
    val matchResult = deviceOsPattern.find(label)
    return if (matchResult != null) {
        val (os, version) = matchResult.destructured
        val name = label.substring(0, matchResult.range.first).trim()
        (name to (os to version))
    } else {
        label to (null to null)
    }
}

internal data class ConnectionRequestState(
    val id: String,
    val clientName: String,
    val clientVersion: String,
    val deviceFingerprint: String,
    val pairingWords: List<String>,
    val status: ConnectionRequestStatus,
)

/** The screen for a session: the main screen, connected, or the request the user acts on first. */
internal fun popupScreen(
    sessionId: String?,
    pendingRequests: List<ConnectionRequestState>,
    isConnected: Boolean,
): PopupScreen {
    if (sessionId == null) return PopupScreen.Inactive
    if (isConnected) return PopupScreen.Connected
    val pairing = pendingRequests.firstOrNull { it.status == ConnectionRequestStatus.AWAITING_PAIRING }
    if (pairing != null) return PopupScreen.ConnectionPairing(pairing)
    val request = pendingRequests.firstOrNull { it.status == ConnectionRequestStatus.PENDING }
        ?: pendingRequests.firstOrNull()
    // A session with nothing to answer is an open pairing window: its details ride on the main screen.
    return request?.let(PopupScreen::ConnectionRequest) ?: PopupScreen.Inactive
}

/** `host:port` of a link endpoint URL, or `null` when it is not one. */
internal fun linkAddress(endpoint: String): String? =
    runCatching { java.net.URI(endpoint) }.getOrNull()
        ?.takeIf { !it.host.isNullOrBlank() && it.port > 0 }
        ?.let { "${it.host}:${it.port}" }
