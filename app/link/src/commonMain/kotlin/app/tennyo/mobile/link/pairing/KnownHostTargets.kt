package app.tennyo.mobile.link.pairing

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.crypto.HostShortId
import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.store.SettingKey
import app.tennyo.mobile.link.store.get
import app.tennyo.mobile.link.store.set
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable

data class KnownHostTarget(
    val companionId: String,
    val companionLabel: String,
    val lastProjectName: String? = null,
    val hostVersion: String,
    val lastUsedAtEpochMillis: Long,
    val discoveryTransportFingerprint: String,
    val lastResolvedHost: String? = null,
    val lastKnownNetworkLabel: String? = null,
    /** Every address the host's last ticket carried, best first. */
    val ticketEndpoints: List<String> = emptyList(),
    /** Addresses the user typed, at most [MAX_ADDED_ENDPOINTS]; a ticket never replaces them. */
    val addedEndpoints: List<String> = emptyList(),
) {
    /** The same id the pairing rows show, so a host reads the same in both lists. */
    val displayShortId: String get() = HostShortId.display(HostShortId.of(discoveryTransportFingerprint))

    /** The addresses to dial when DNS-SD misses the host: the one that answered last, the ticket's, the typed ones. */
    fun dialEndpoints(): List<String> =
        (listOfNotNull(lastResolvedHost) + ticketEndpoints + addedEndpoints).distinct().take(MAX_DIAL_ENDPOINTS)

    private companion object {
        const val MAX_DIAL_ENDPOINTS = 8
    }
}

const val MAX_ADDED_ENDPOINTS = 4

interface KnownHostTargetStore {
    fun list(): List<KnownHostTarget>
    fun get(companionId: String): KnownHostTarget?
    fun save(target: KnownHostTarget)
    fun remove(companionId: String)
    fun updateFromDescriptor(
        descriptor: KnownHostDescriptor,
        transportFingerprint: String,
        resolvedHost: String?,
    )
}

class SettingsKnownHostTargetStore(private val settings: Settings) : KnownHostTargetStore {
    override fun list(): List<KnownHostTarget> =
        snapshot().decodedTargets()
            .sortedByDescending { it.lastUsedAtEpochMillis }

    override fun get(companionId: String): KnownHostTarget? =
        snapshot().decodedTargets().firstOrNull { it.companionId == companionId }

    override fun save(target: KnownHostTarget) {
        val next = snapshot().decodedTargets()
            .filterNot { it.companionId == target.companionId }
            .plus(target)
            .sortedByDescending { it.lastUsedAtEpochMillis }
        discoveryDebug {
            "store save target companion=${target.companionId.debugShortId()} " +
                    "lastProject=${target.lastProjectName.debugQuotedOrNone()} " +
                    "endpoint=${target.lastResolvedHost.debugQuotedOrNone()} total=${next.size}"
        }
        saveSnapshot(knownHostTargetSnapshotOf(next))
    }

    override fun remove(companionId: String) {
        val current = snapshot()
        val currentTargets = current.decodedTargets()
        val next = currentTargets.filterNot { it.companionId == companionId }
        if (next.size != currentTargets.size) {
            discoveryDebug {
                "store remove target companion=${companionId.debugShortId()} remaining=${next.size}"
            }
            saveSnapshot(knownHostTargetSnapshotOf(next))
        }
    }

    override fun updateFromDescriptor(
        descriptor: KnownHostDescriptor,
        transportFingerprint: String,
        resolvedHost: String?,
    ) {
        val existing = get(descriptor.companionId)
        discoveryDebug {
            "store update from descriptor companion=${descriptor.companionId.debugShortId()} " +
                    "label=${descriptor.companionLabel.debugQuotedOrNone()} host=${resolvedHost.debugQuotedOrNone()} " +
                    "existing=${existing != null}"
        }
        save(
            KnownHostTarget(
                companionId = descriptor.companionId,
                companionLabel = descriptor.companionLabel.ifBlank { existing?.companionLabel.orEmpty() },
                lastProjectName = descriptor.projectName
                    .ifBlank { existing?.lastProjectName.orEmpty() }
                    .takeIf { it.isNotBlank() },
                hostVersion = descriptor.hostVersion,
                // Discovery only proves that a paired IDE is reachable. Keep this as the
                // last authenticated session connection rather than a discovery probe time.
                lastUsedAtEpochMillis = existing?.lastUsedAtEpochMillis ?: descriptor.lastUsedAtEpochMillis,
                discoveryTransportFingerprint = transportFingerprint.ifBlank {
                    existing?.discoveryTransportFingerprint.orEmpty()
                },
                lastResolvedHost = resolvedHost ?: existing?.lastResolvedHost,
                lastKnownNetworkLabel = descriptor.lastKnownNetworkLabel ?: existing?.lastKnownNetworkLabel,
                ticketEndpoints = existing?.ticketEndpoints.orEmpty(),
                addedEndpoints = existing?.addedEndpoints.orEmpty(),
            )
        )
    }

    private fun snapshot(): KnownHostTargetSnapshot = settings[KnownHostTargetsKey]

    private fun saveSnapshot(snapshot: KnownHostTargetSnapshot) {
        settings[KnownHostTargetsKey] = snapshot
    }
}

@Serializable
private data class KnownHostTargetSnapshot(
    val targets: List<KnownHostTargetPayload> = emptyList(),
)

@Serializable
private data class KnownHostTargetPayload(
    val companionId: String,
    val companionLabel: String,
    val lastProjectName: String? = null,
    val hostVersion: String,
    val lastUsedAtEpochMillis: Long,
    val discoveryTransportFingerprint: String,
    val lastResolvedHost: String? = null,
    val lastKnownNetworkLabel: String? = null,
    val ticketEndpoints: List<String> = emptyList(),
    val addedEndpoints: List<String> = emptyList(),
)

private fun KnownHostTargetSnapshot.decodedTargets(): List<KnownHostTarget> =
    targets.map {
        KnownHostTarget(
            companionId = it.companionId,
            companionLabel = it.companionLabel,
            lastProjectName = it.lastProjectName,
            hostVersion = it.hostVersion,
            lastUsedAtEpochMillis = it.lastUsedAtEpochMillis,
            discoveryTransportFingerprint = it.discoveryTransportFingerprint,
            lastResolvedHost = it.lastResolvedHost,
            lastKnownNetworkLabel = it.lastKnownNetworkLabel,
            ticketEndpoints = it.ticketEndpoints,
            addedEndpoints = it.addedEndpoints,
        )
    }

private fun knownHostTargetSnapshotOf(
    targets: List<KnownHostTarget>,
): KnownHostTargetSnapshot =
    KnownHostTargetSnapshot(
        targets = targets.map {
            KnownHostTargetPayload(
                companionId = it.companionId,
                companionLabel = it.companionLabel,
                lastProjectName = it.lastProjectName,
                hostVersion = it.hostVersion,
                lastUsedAtEpochMillis = it.lastUsedAtEpochMillis,
                discoveryTransportFingerprint = it.discoveryTransportFingerprint,
                lastResolvedHost = it.lastResolvedHost,
                lastKnownNetworkLabel = it.lastKnownNetworkLabel,
                ticketEndpoints = it.ticketEndpoints,
                addedEndpoints = it.addedEndpoints,
            )
        }
    )

private val KnownHostTargetsKey =
    SettingKey.json("link.knownHostTargets", KnownHostTargetSnapshot.serializer(), KnownHostTargetSnapshot())
