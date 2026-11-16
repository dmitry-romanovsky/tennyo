package app.tennyo.host.vscode.session

import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.discovery.NodeDnsSdAdvertiser
import app.tennyo.host.engine.pairing.NodeRememberedPairStore
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.pairing.nodeCompanionId
import app.tennyo.host.engine.panel.DiscoveryBeforePairing
import app.tennyo.host.engine.panel.HostPanelActions
import app.tennyo.host.engine.panel.HostPanelModel
import app.tennyo.host.engine.panel.HostPanelState
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.engine.panel.NoSessionReason
import app.tennyo.host.engine.panel.RemoteAccessPanel
import app.tennyo.host.engine.panel.SessionPanelModel
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.HostRegistry
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.openPairingWindow
import app.tennyo.host.engine.transport.NodeLinkServer
import app.tennyo.host.vscode.agents.DevinAgents
import app.tennyo.host.vscode.agents.VsCodeAgentHost
import app.tennyo.host.vscode.api.Memento
import app.tennyo.host.vscode.panes.vsCodeProjectPanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The companion's lifetime inside one VS Code window: the listener, the session host for the open folder, LAN
 * discovery, and the panel model over all three.
 *
 * A window with no folder still has a mode: "No one" and "Known devices" are saved and apply the moment a folder
 * opens, and "All" asks for a folder first, because a session has nothing to serve without one. Opening a folder —
 * into this window or by reloading it — builds the host then, rather than only at activation.
 */
class VsCodeCompanionHost(
    private val hostVersion: String,
    private val globalState: Memento,
    private val workspaceState: Memento,
    userDataPath: String,
    private val onStateChanged: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val agentHost = VsCodeAgentHost(userDataPath, scope)
    private val devinAgents = DevinAgents(scope)
    private val companionId = nodeCompanionId()
    private val pairStore = NodeRememberedPairStore()
    private val discovery: VsCodeDiscovery = VsCodeDiscovery(
        companionId = companionId,
        hostLabel = hostVersion,
        settings = MementoSettingsStore(globalState),
        advertiser = NodeDnsSdAdvertiser(),
        onBlocked = { listener.reconcile() },
    )
    private val listener: NodeLinkServer = NodeLinkServer(
        scope,
        discoveryRoutes = DiscoveryRoutes(discovery),
        remoteAccess = discovery.lockout::isRemoteAccessEnabled,
    )
    private val discoveryBeforePairing = DiscoveryBeforePairing()
    private var host: SessionHost? = null
    private var binding = false

    /** The panel's view of this window, which reports what the host can do at the moment it is asked. */
    val panelModel: HostPanelModel = object : HostPanelModel {
        override val state: HostPanelState
            get() = host?.let { panelModelFor(it).state } ?: HostPanelState.NoSession(
                visibility = if (discovery.isEnabled()) HostVisibility.KNOWN_DEVICES else HostVisibility.NO_ONE,
                knownDeviceCount = pairStore.summaries(hostWallClockMillis()).size,
                reason = NoSessionReason.LISTENER_UNAVAILABLE,
            )

        override fun openPairingWindow() = withHost { it.openPairingWindow() }

        override fun extendPairingWindow() = withHost { it.extendPairingWindow() }

        override fun setVisibility(visibility: HostVisibility) {
            val current = host
            when {
                current != null -> panelModelFor(current).setVisibility(visibility)
                // No folder yet: the mode is saved and waits for one. "All" is the panel's to ask a folder for.
                visibility != HostVisibility.ALL ->
                    actions.setDiscoveryEnabled(visibility == HostVisibility.KNOWN_DEVICES)
            }
        }

        override val remoteAccess: RemoteAccessPanel
            get() = host?.let { panelModelFor(it).remoteAccess } ?: RemoteAccessPanel(
                enabled = discovery.lockout.isRemoteAccessEnabled(),
                available = false,
                port = null,
                overlay = emptyList(),
            )

        override fun setRemoteAccess(enabled: Boolean) = actions.setRemoteAccess(enabled)

        override fun acceptRequest() = withHost { it.acceptRequest() }

        override fun rejectRequest() = withHost { it.rejectRequest() }

        override fun endSession() = withHost { it.endSession() }

        // A window with no folder has no session host, but its phones are still the shared pair store's.
        override fun knownDevices(): List<RememberedPairSummary> = host?.let { panelModelFor(it).knownDevices() }
            ?: pairStore.summaries(hostWallClockMillis()).sortedByDescending { it.lastUsedAtEpochMillis }

        override fun forgetDevice(pairId: String) {
            host?.let { panelModelFor(it).forgetDevice(pairId) } ?: pairStore.revoke(pairId)
            changed()
        }

        private fun withHost(action: (HostPanelModel) -> Unit) {
            host?.let { action(panelModelFor(it)) }
        }
    }

    private val actions = object : HostPanelActions {
        override fun openPairingWindow() {
            val current = host ?: return
            discoveryBeforePairing.remember(discovery.isEnabled())
            current.openPairingWindow()
            discovery.setEnabled(true)
            changed()
        }

        override fun setDiscoveryEnabled(enabled: Boolean) {
            discoveryBeforePairing.forget()
            discovery.setEnabled(enabled)
            changed()
        }

        override fun setRemoteAccess(enabled: Boolean) {
            discovery.lockout.setRemoteAccess(enabled)
            listener.reconcile()
            changed()
        }
    }

    /** Builds the host for the open folder, if there is one and it is not built yet. */
    fun start(onReady: (Boolean) -> Unit = {}) {
        if (host != null || binding) return
        val panes = vsCodeProjectPanes(hostVersion, agentHost, devinAgents) { host }
        if (panes == null) {
            LOG.info("Tennyo has no folder to serve in this window.")
            onReady(false)
            return
        }
        binding = true
        val preferredPort = (workspaceState.get(LISTENER_PORT_KEY) as? Number)?.toInt()
        listener.acquire(preferredPort = preferredPort, ownerKind = "vscode-window") { bound ->
            binding = false
            bound.onFailure { failure ->
                LOG.warn("Tennyo could not bind its listener: ${failure.message}")
                changed()
                onReady(false)
            }.onSuccess { lease ->
                val platform = VsCodeHostPlatform(
                    listener = listener,
                    companionId = companionId,
                    hostLabel = hostVersion,
                    onStateChanged = ::changed,
                    onSessionsEnded = ::restoreModeBeforePairing,
                    discoveryEnabled = discovery::isEnabled,
                    remoteAccessEnabled = discovery.lockout::isRemoteAccessEnabled,
                    pairStore = pairStore,
                )
                val built = SessionHost(panes, platform, scope).also(HostRegistry::register)
                host = built
                // Each folder keeps its port across restarts, so a phone's stored address stays valid.
                workspaceState.update(LISTENER_PORT_KEY, lease.endpoints().port)
                discovery.serve(built, panes.projectInfo, lease.endpoints)
                LOG.info("Tennyo is listening for ${panes.projectInfo.name}.")
                changed()
                onReady(true)
            }
        }
    }

    fun stop() {
        host?.let { session ->
            HostRegistry.unregister(session)
            session.close()
        }
        host = null
        discovery.close()
        listener.close()
        scope.cancel()
    }

    /** The pairing session is over: put back the mode "All" overrode. */
    private fun restoreModeBeforePairing() {
        discoveryBeforePairing.takeSaved()?.let(discovery::setEnabled)
        changed()
    }

    private fun changed() {
        discovery.refreshAdvertisement()
        onStateChanged()
    }

    private fun panelModelFor(session: SessionHost): HostPanelModel = SessionPanelModel(session, actions)

    private companion object {
        val LOG = HostLogger("VsCodeCompanionHost")
        const val LISTENER_PORT_KEY = "tennyo.listenerPort"
    }
}
