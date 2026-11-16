package app.tennyo.host.engine.discovery

import app.tennyo.contract.ProjectChoice
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.serialization.Serializable

/** Whether LAN discovery is on, who owns it, and what the lockout has counted. The IDE persists it. */
@Serializable
data class DiscoveryTrustState(
    val enabled: Boolean = false,
    val blockedMessage: String? = null,
    val blockedAtEpochMillis: Long = 0L,
    val blockedBannerDismissed: Boolean = false,
    val countedFailureEpochMillis: List<Long> = emptyList(),
    val lastAttackerSource: String? = null,
    val ownerProjectId: String? = null,
    /** Whether callers away from the local network may connect; off until the user turns it on. */
    val remoteAccess: Boolean = false,
)

/** Where the IDE keeps [DiscoveryTrustState] between runs. */
interface HostSettingsStore {
    var trust: DiscoveryTrustState
}

/**
 * The discovery on/off and lockout rules. Five counted security failures within fifteen minutes turn discovery off
 * and block it with the last failure's message until the user resets trust; blocking or resetting also turns Remote
 * access off, so after an attack the user switches it on again on purpose. Each transition reports whether the IDE
 * must re-evaluate which project advertises.
 *
 * Every transition reads and rewrites the whole state under one monitor: failures arrive on concurrent listener
 * threads, and a lost update would let parallel failures stay under the lockout.
 */
class DiscoveryLockoutPolicy(private val store: HostSettingsStore) {
    private val monitor = HostMonitor()

    fun isEnabled(): Boolean = store.trust.enabled

    fun ownerProjectId(): String? = store.trust.ownerProjectId

    fun isRemoteAccessEnabled(): Boolean = store.trust.remoteAccess

    fun setRemoteAccess(enabled: Boolean) = update { it.copy(remoteAccess = enabled) }

    fun blockedMessage(): String? = store.trust.blockedMessage?.takeIf { it.isNotBlank() }

    fun blockedBannerDismissed(): Boolean = store.trust.blockedBannerDismissed

    /** Turning discovery on makes [ownerProjectId] the owner; turning it off keeps the previous owner. */
    fun setEnabled(ownerProjectId: String, enabled: Boolean) = update { trust ->
        trust.copy(
            enabled = enabled,
            ownerProjectId = if (enabled) ownerProjectId else trust.ownerProjectId,
            blockedBannerDismissed = false,
        )
    }

    fun dismissBlockedBanner() = update { it.copy(blockedBannerDismissed = true) }

    fun resetTrust() = update {
        it.copy(
            enabled = false,
            blockedMessage = null,
            blockedAtEpochMillis = 0L,
            blockedBannerDismissed = false,
            countedFailureEpochMillis = emptyList(),
            lastAttackerSource = null,
            remoteAccess = false,
        )
    }

    /** Counts one failure; returns `true` when it is the one that blocks discovery. */
    fun recordSecurityFailure(
        message: String,
        attackerSource: String?,
        nowEpochMillis: Long = hostWallClockMillis(),
    ): Boolean = monitor.guarded {
        val trust = store.trust
        val counted = trust.countedFailureEpochMillis.filter { nowEpochMillis - it <= FAILURE_WINDOW_MILLIS } +
                nowEpochMillis
        val blocks = counted.size >= MAX_FAILURES
        val next = trust.copy(
            countedFailureEpochMillis = counted,
            blockedBannerDismissed = false,
            lastAttackerSource = attackerSource,
        )
        store.trust = if (!blocks) next else next.copy(
            enabled = false,
            blockedMessage = message,
            blockedAtEpochMillis = nowEpochMillis,
            remoteAccess = false,
        )
        blocks
    }

    private fun update(transform: (DiscoveryTrustState) -> DiscoveryTrustState) = monitor.guarded {
        store.trust = transform(store.trust)
    }

    companion object {
        const val FAILURE_WINDOW_MILLIS = 15 * 60 * 1_000L
        const val MAX_FAILURES = 5

        /** The project that answers discovery: the chosen owner while it is open, otherwise the first open one. */
        fun resolveDiscoveryOwnerProjectId(
            preferredOwnerProjectId: String?,
            registeredProjectIds: Collection<String>,
        ): String? {
            val preferredOwner = preferredOwnerProjectId?.takeIf { it in registeredProjectIds }
            return preferredOwner ?: registeredProjectIds.firstOrNull()
        }

        /** The project that advertises on the LAN: nobody while discovery is off or any session is running. */
        fun resolveDiscoveryAdvertisementOwnerProjectId(
            enabled: Boolean,
            preferredOwnerProjectId: String?,
            registeredProjectIds: Collection<String>,
            activeSessionOwnerProjectIds: Set<String>,
        ): String? {
            if (!enabled || activeSessionOwnerProjectIds.isNotEmpty()) return null
            return resolveDiscoveryOwnerProjectId(preferredOwnerProjectId, registeredProjectIds)
        }

        fun orderDiscoveryProjectChoices(
            preferredOwnerProjectId: String?,
            projectChoices: List<ProjectChoice>,
        ): List<ProjectChoice> =
            projectChoices.sortedWith(
                compareByDescending<ProjectChoice> { it.projectId == preferredOwnerProjectId }
                    .thenBy { it.projectName.lowercase() }
            )
    }
}

/** Where the IDE keeps the companion id so that it survives a lost settings file. */
interface HostIdentityStore {
    fun load(): String?

    fun save(companionId: String)
}

enum class HostIdentitySource {
    DurableStore,
    GlobalSettingsState,
    Generated,
}

data class HostIdentityResolution(
    val companionId: String,
    val source: HostIdentitySource,
)

/**
 * Finds the companion id: the durable store wins over the settings file, and a new id is drawn only when neither has
 * one and [createIfMissing] allows it.
 */
// Each source answers on its own: the first that holds an id ends the search.
@Suppress("ReturnCount")
fun recoverCompanionIdentity(
    stateCompanionId: String,
    durableCompanionId: String?,
    createIfMissing: Boolean,
    newCompanionId: () -> String = ::hostRandomUuid,
): HostIdentityResolution? {
    durableCompanionId
        ?.takeIf(String::isNotBlank)
        ?.let { return HostIdentityResolution(it, HostIdentitySource.DurableStore) }

    stateCompanionId
        .takeIf(String::isNotBlank)
        ?.let { return HostIdentityResolution(it, HostIdentitySource.GlobalSettingsState) }

    if (!createIfMissing) return null
    return HostIdentityResolution(newCompanionId(), HostIdentitySource.Generated)
}
