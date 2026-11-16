package app.tennyo.host.intellij

import app.tennyo.contract.ProjectChoice
import app.tennyo.host.engine.discovery.DiscoveryLockoutPolicy
import app.tennyo.host.engine.discovery.DiscoveryTrustState
import app.tennyo.host.engine.discovery.HostIdentityResolution
import app.tennyo.host.engine.discovery.HostIdentityStore
import app.tennyo.host.engine.discovery.HostSettingsStore
import app.tennyo.host.engine.discovery.recoverCompanionIdentity
import app.tennyo.host.engine.platform.debugQuotedOrNone
import app.tennyo.host.engine.platform.debugShortId
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * The app-wide companion settings: the companion id, the preferred port, and the discovery trust state, persisted in
 * `tennyo-companion-global.xml`. [DiscoveryLockoutPolicy] decides the trust transitions; this service persists them
 * and keeps the one advertising project in step with the open projects. Everything here belongs to this machine, so
 * settings sync never roams it.
 */
@Service(Service.Level.APP)
@State(
    name = "HostGlobalSettings",
    storages = [Storage("tennyo-companion-global.xml", roamingType = RoamingType.DISABLED)],
)
class HostGlobalSettings() : PersistentStateComponent<HostGlobalSettings.State>,
    HostSettingsStore {
    internal constructor(companionIdentityStore: HostIdentityStore) : this() {
        this.companionIdentityStore = companionIdentityStore
    }

    private var companionIdentityStore: HostIdentityStore = PasswordSafeIdentityStore()
    @Volatile
    private var state = State()
    private val lock = Any()

    /** Guards [state] alone, so a trust update never waits behind the keychain I/O that [lock] covers. */
    private val stateLock = Any()
    private val lockout = DiscoveryLockoutPolicy(this)
    private val registeredServices = linkedMapOf<String, HostSessionService>()
    private val activeSessionOwners = linkedSetOf<String>()

    @Volatile
    private var registeredServiceRefreshGeneration = 0L

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    override var trust: DiscoveryTrustState
        get() = state.let {
            DiscoveryTrustState(
                enabled = it.enabled,
                blockedMessage = it.blockedMessage,
                blockedAtEpochMillis = it.blockedAtEpochMillis,
                blockedBannerDismissed = it.blockedBannerDismissed,
                countedFailureEpochMillis = it.countedFailureEpochMillis,
                lastAttackerSource = it.lastAttackerSource,
                ownerProjectId = it.ownerProjectLocationHash,
                remoteAccess = it.remoteAccess,
            )
        }
        set(value) = updateState {
            it.copy(
                enabled = value.enabled,
                blockedMessage = value.blockedMessage,
                blockedAtEpochMillis = value.blockedAtEpochMillis,
                blockedBannerDismissed = value.blockedBannerDismissed,
                countedFailureEpochMillis = value.countedFailureEpochMillis,
                lastAttackerSource = value.lastAttackerSource,
                ownerProjectLocationHash = value.ownerProjectId,
                remoteAccess = value.remoteAccess,
            )
        }

    fun isEnabled(): Boolean = lockout.isEnabled()

    fun setEnabled(
        ownerProject: Project,
        enabled: Boolean,
    ) {
        lockout.setEnabled(ownerProject.locationHash, enabled)
        if (enabled) {
            companionId()
        }
        tennyoDiscoveryDebug(LOG) {
            "global discovery enabled=$enabled owner=${ownerProject.name.debugQuotedOrNone()} " +
                    "ownerHash=${lockout.ownerProjectId().debugQuotedOrNone()}"
        }
        scheduleRegisteredServiceRefresh()
    }

    fun isRemoteAccessEnabled(): Boolean = lockout.isRemoteAccessEnabled()

    fun setRemoteAccess(enabled: Boolean) {
        lockout.setRemoteAccess(enabled)
        tennyoDiscoveryDebug(LOG) { "global remoteAccess=$enabled" }
    }

    fun companionId(): String {
        return ensureCompanionIdentity(createIfMissing = true)
            ?: error("Companion identity should exist after generation")
    }

    fun currentCompanionIdOrNull(): String? =
        ensureCompanionIdentity(createIfMissing = false)

    fun preferredPort(): Int? =
        state.preferredPort.takeIf { it > 0 }

    fun updatePreferredPort(port: Int) {
        updateState { it.copy(preferredPort = port) }
        tennyoDiscoveryDebug(LOG) { "global preferredPort=$port" }
    }

    fun blockedMessage(): String? = lockout.blockedMessage()

    fun blockedBannerDismissed(): Boolean = lockout.blockedBannerDismissed()

    fun dismissBlockedBanner() {
        lockout.dismissBlockedBanner()
        tennyoDiscoveryDebug(LOG) { "global blocked banner dismissed" }
    }

    fun resetDiscoveryTrust() {
        lockout.resetTrust()
        reconcileListener()
        tennyoDiscoveryDebug(LOG) { "global discovery trust reset" }
        scheduleRegisteredServiceRefresh()
    }

    fun recordSecurityFailure(
        message: String,
        attackerSource: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!lockout.recordSecurityFailure(message, attackerSource, nowEpochMillis)) return false
        reconcileListener()
        tennyoDiscoveryDebug(LOG) {
            "global discovery blocked attacker=${attackerSource.debugQuotedOrNone()} message=${message.debugQuotedOrNone()}"
        }
        scheduleRegisteredServiceRefresh()
        return true
    }

    /** Remote access went off with the trust state, so the listener drops its overlay sockets now, off this thread. */
    private fun reconcileListener() {
        AppExecutorUtil.getAppExecutorService().execute { service<LinkServerService>().reconcile() }
    }

    fun registerSessionService(
        project: Project,
        service: HostSessionService,
    ) {
        synchronized(lock) {
            registeredServices[project.locationHash] = service
        }
        scheduleRegisteredServiceRefresh()
    }

    fun unregisterSessionService(project: Project) {
        synchronized(lock) {
            registeredServices.remove(project.locationHash)
            activeSessionOwners.remove(project.locationHash)
        }
        scheduleRegisteredServiceRefresh()
    }

    fun markSessionActive(
        project: Project,
        active: Boolean,
    ) {
        synchronized(lock) {
            if (active) {
                activeSessionOwners += project.locationHash
            } else {
                activeSessionOwners -= project.locationHash
            }
        }
        scheduleRegisteredServiceRefresh()
    }

    fun hasActiveSession(): Boolean =
        synchronized(lock) { activeSessionOwners.isNotEmpty() }

    fun isOwner(project: Project): Boolean =
        synchronized(lock) { effectiveOwnerProjectIdLocked() == project.locationHash }

    fun ownerService(companionId: String? = currentCompanionIdOrNull()): HostSessionService? {
        val expectedCompanionId = companionId?.takeIf { it.isNotBlank() } ?: return null
        val currentCompanionId = currentCompanionIdOrNull()
        if (expectedCompanionId != currentCompanionId || !state.enabled) return null
        return synchronized(lock) { effectiveOwnerProjectIdLocked()?.let(registeredServices::get) }
    }

    internal fun projectService(projectId: String): HostSessionService? =
        synchronized(lock) { registeredServices[projectId] }

    /** The registered project whose pairing window is open; at most one is, because opening one starts a session. */
    internal fun pairingWindowService(): HostSessionService? =
        synchronized(lock) { registeredServices.values.toList() }
            .firstOrNull { it.host.pairingWindow.current() != null }

    internal fun availableDiscoveryProjectChoices(): List<ProjectChoice> {
        return synchronized(lock) {
            DiscoveryLockoutPolicy.orderDiscoveryProjectChoices(
                preferredOwnerProjectId = effectiveOwnerProjectIdLocked(),
                projectChoices = registeredServices.values.map { it.project.toRemoteProjectChoice() },
            )
        }
    }

    private fun ensureCompanionIdentity(createIfMissing: Boolean): String? {
        synchronized(lock) {
            val recoveredIdentity = recoverCompanionIdentityLocked(createIfMissing) ?: return null
            val currentStateCompanionId = state.companionId.takeIf { it.isNotBlank() }
            if (currentStateCompanionId != recoveredIdentity.companionId) {
                updateState { it.copy(companionId = recoveredIdentity.companionId) }
            }
            if (companionIdentityStore.load() != recoveredIdentity.companionId) {
                companionIdentityStore.save(recoveredIdentity.companionId)
            }
            tennyoDiscoveryDebug(LOG) {
                "resolved global companionId=${recoveredIdentity.companionId.debugShortId()} " +
                        "source=${recoveredIdentity.source.name}"
            }
            return recoveredIdentity.companionId
        }
    }

    /** Every write copies the whole state, so writers from different threads take turns rather than lose updates. */
    private fun updateState(transform: (State) -> State) {
        synchronized(stateLock) { state = transform(state) }
    }

    private fun recoverCompanionIdentityLocked(createIfMissing: Boolean): HostIdentityResolution? =
        recoverCompanionIdentity(
            stateCompanionId = state.companionId,
            durableCompanionId = companionIdentityStore.load(),
            createIfMissing = createIfMissing,
        )

    private fun scheduleRegisteredServiceRefresh() {
        val generation = synchronized(lock) {
            registeredServiceRefreshGeneration += 1L
            registeredServiceRefreshGeneration
        }
        AppExecutorUtil.getAppExecutorService().execute { refreshRegisteredServices(generation) }
    }

    private fun refreshRegisteredServices(generation: Long) {
        val services: List<HostSessionService>
        val ownerService: HostSessionService?
        val shouldAdvertise: Boolean
        val companionId = currentCompanionIdOrNull()
        synchronized(lock) {
            if (generation != registeredServiceRefreshGeneration) return
            services = registeredServices.values.toList()
            val advertisingOwnerProjectId = DiscoveryLockoutPolicy.resolveDiscoveryAdvertisementOwnerProjectId(
                enabled = state.enabled,
                preferredOwnerProjectId = state.ownerProjectLocationHash,
                registeredProjectIds = registeredServices.keys,
                activeSessionOwnerProjectIds = activeSessionOwners,
            )
            ownerService = advertisingOwnerProjectId?.let(registeredServices::get)
            shouldAdvertise = ownerService != null
        }
        services.forEach { service ->
            if (isStaleRegisteredServiceRefresh(generation)) return
            service.updateDiscoveryAdvertisement(
                shouldAdvertise = shouldAdvertise && service === ownerService,
            )
        }
        if (!shouldAdvertise && !isStaleRegisteredServiceRefresh(generation)) {
            companionId?.let(DnsSdAdvertisement::unadvertise)
        }
    }

    private fun effectiveOwnerProjectIdLocked(): String? =
        DiscoveryLockoutPolicy.resolveDiscoveryOwnerProjectId(
            preferredOwnerProjectId = state.ownerProjectLocationHash,
            registeredProjectIds = registeredServices.keys,
        )

    private fun isStaleRegisteredServiceRefresh(generation: Long): Boolean =
        synchronized(lock) { generation != registeredServiceRefreshGeneration }

    data class State(
        var enabled: Boolean = false,
        var companionId: String = "",
        var preferredPort: Int = 0,
        var blockedMessage: String? = null,
        var blockedAtEpochMillis: Long = 0L,
        var blockedBannerDismissed: Boolean = false,
        var countedFailureEpochMillis: List<Long> = emptyList(),
        var lastAttackerSource: String? = null,
        var ownerProjectLocationHash: String? = null,
        var remoteAccess: Boolean = false,
    )

    companion object {
        private val LOG = Logger.getInstance(HostGlobalSettings::class.java)
    }
}
