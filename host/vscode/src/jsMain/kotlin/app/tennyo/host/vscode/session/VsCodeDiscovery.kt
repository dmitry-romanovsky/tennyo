package app.tennyo.host.vscode.session

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.ProjectChoice
import app.tennyo.host.engine.discovery.DiscoveryAdvertiser
import app.tennyo.host.engine.discovery.DiscoveryLockoutPolicy
import app.tennyo.host.engine.discovery.DiscoveryMessage
import app.tennyo.host.engine.discovery.DiscoveryProject
import app.tennyo.host.engine.discovery.DiscoveryTrustState
import app.tennyo.host.engine.discovery.HostSettingsStore
import app.tennyo.host.engine.discovery.ProjectDirectory
import app.tennyo.host.engine.panes.HostProjectInfo
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.hasClaimedSession
import app.tennyo.host.engine.session.latestSuccessfulRemoteConnectionAt
import app.tennyo.host.vscode.api.Memento

/**
 * LAN discovery for this window: whether it is on, the lockout that turns it off, the one project it answers for, and
 * the DNS-SD advertisement a phone browses for. The rules match IntelliJ's: advertise while discovery is on, nothing
 * is blocked, and no session is claimed — a session behind an open pairing window still advertises.
 */
class VsCodeDiscovery(
    private val companionId: String,
    private val hostLabel: String,
    private val settings: HostSettingsStore,
    private val advertiser: DiscoveryAdvertiser,
    /** Called when the lockout blocks discovery, which also turns Remote access off: the listener must follow. */
    private val onBlocked: () -> Unit = {},
) : ProjectDirectory {
    val lockout = DiscoveryLockoutPolicy(settings)

    /** The project this window serves, once it has a folder and a listener. */
    var project: DiscoveryProject? = null
        private set

    fun serve(host: SessionHost, info: HostProjectInfo, endpoints: () -> HostEndpoints) {
        project = WindowProject(host, info, endpoints)
        refreshAdvertisement()
    }

    fun isEnabled(): Boolean = lockout.isEnabled()

    fun setEnabled(enabled: Boolean) {
        lockout.setEnabled(project?.projectId.orEmpty(), enabled)
        refreshAdvertisement()
    }

    /** Re-applies the advertisement rule; cheap to call on every state change, the advertiser skips no-op updates. */
    fun refreshAdvertisement() {
        val served = project
        val shouldAdvertise = served != null && lockout.isEnabled() && lockout.blockedMessage() == null &&
            !served.host.hasClaimedSession
        if (served == null || !shouldAdvertise) {
            advertiser.unadvertise(companionId)
            return
        }
        advertiser.advertise(
            companionId = companionId,
            endpoint = served.discoveryEndpoints().primary,
            pairingWindowOpen = served.host.pairingWindow.current() != null,
        )
    }

    fun close() = advertiser.unadvertise(companionId)

    override fun discoveryOwner(companionId: String): DiscoveryProject? =
        project?.takeIf { companionId == this.companionId && lockout.isEnabled() }

    override fun pairingWindowOwner(): DiscoveryProject? =
        project?.takeIf { it.host.pairingWindow.current() != null }

    override fun project(projectId: String): DiscoveryProject? = project?.takeIf { it.projectId == projectId }

    override fun message(message: DiscoveryMessage): String = when (message) {
        DiscoveryMessage.CONNECT_DISABLED -> "Tennyo is turned off in the editor."
        DiscoveryMessage.PAIRING_REQUIRED -> "Pair this phone again: open pairing mode in the editor."
        DiscoveryMessage.PROJECT_NOT_FOUND -> "That folder is no longer open in the editor."
        DiscoveryMessage.SESSION_UNAVAILABLE -> "The editor could not start a session. Try again."
    }

    private inner class WindowProject(
        override val host: SessionHost,
        private val info: HostProjectInfo,
        private val endpoints: () -> HostEndpoints,
    ) : DiscoveryProject {
        override val projectId: String get() = info.id

        override fun isDiscoveryEnabled(): Boolean = lockout.isEnabled()

        override fun isDiscoveryAvailable(): Boolean = lockout.isEnabled() && !host.hasClaimedSession

        override fun discoveryBlockedMessage(): String? = lockout.blockedMessage()

        override fun discoveryProjectChoices(): List<ProjectChoice> =
            listOf(ProjectChoice(projectId = info.id, projectName = info.name, projectPath = info.displayPath))

        override fun discoveryDescriptor(): KnownHostDescriptor = KnownHostDescriptor(
            companionId = companionId,
            companionLabel = hostLabel,
            projectName = info.name,
            hostVersion = info.hostVersion,
            lastUsedAtEpochMillis = latestSuccessfulRemoteConnectionAt(companionId, host.rememberedPairs()),
            lastKnownNetworkLabel = info.displayPath,
        )

        override fun discoveryEndpoints(): HostEndpoints = endpoints()

        override fun recordDiscoverySecurityFailure(message: String, attackerSource: String?): Boolean =
            lockout.recordSecurityFailure(message, attackerSource).also { blocked ->
                if (blocked) {
                    refreshAdvertisement()
                    onBlocked()
                }
            }
    }
}

/**
 * The discovery trust state in VS Code's global state, which every window shares. A first run starts in "Known
 * devices", the mode a phone that has paired before expects to find.
 */
class MementoSettingsStore(private val memento: Memento) : HostSettingsStore {
    override var trust: DiscoveryTrustState
        get() = (memento.get(KEY) as? String)
            ?.let { runCatching { HostJson.decodeFromString(DiscoveryTrustState.serializer(), it) }.getOrNull() }
            ?: DiscoveryTrustState(enabled = true)
        set(value) {
            memento.update(KEY, HostJson.encodeToString(DiscoveryTrustState.serializer(), value))
        }

    private companion object {
        const val KEY = "tennyo.discoveryTrust"
    }
}
