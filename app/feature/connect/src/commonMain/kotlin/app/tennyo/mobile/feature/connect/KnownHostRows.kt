package app.tennyo.mobile.feature.connect

import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.link.pairing.KnownHostAnswer
import app.tennyo.mobile.link.pairing.KnownHostReach
import app.tennyo.mobile.link.pairing.KnownHostTarget

internal fun connectError(failure: ConnectFailure, throwable: Throwable?): ConnectErrorUiState =
    ConnectErrorUiState(
        failure = failure,
        details = throwable?.toDetailedMessage()?.let(UiText::Verbatim),
    )

internal fun Throwable.toDetailedMessage(): String =
    generateSequence(this) { it.cause }
        .map { error ->
            buildString {
                append(error::class.simpleName ?: "Error")
                error.message?.takeIf { it.isNotBlank() }?.let { message ->
                    append(": ")
                    append(message)
                }
            }
        }
        .joinToString(separator = "\nCaused by: ")

internal fun toUiState(
    target: KnownHostTarget,
    availability: KnownHostAvailability,
    selectedTargetId: String?,
    reach: KnownHostReach? = null,
): KnownHostTargetUiState =
    KnownHostTargetUiState(
        companionId = target.companionId,
        companionLabel = target.companionLabel,
        displayShortId = target.displayShortId,
        lastProjectName = target.lastProjectName,
        hostVersion = target.hostVersion,
        lastUsedAtEpochMillis = target.lastUsedAtEpochMillis,
        networkLabel = target.lastKnownNetworkLabel ?: target.lastResolvedHost,
        availability = availability,
        actionEnabled = false,
        reach = reach,
        resolvedAddress = target.lastResolvedHost?.hostOfEndpoint(),
    ).withActionEnabled(selectedTargetId)

internal fun mergeKnownTargetRows(
    current: List<KnownHostTargetUiState>,
    storedTargets: List<KnownHostTarget>,
    discoveryAvailability: Map<String, KnownHostAvailability> = emptyMap(),
    discoveryReach: Map<String, KnownHostReach?> = emptyMap(),
    removeMissingTargets: Boolean,
    selectedTargetId: String?,
): List<KnownHostTargetUiState> {
    val currentById = current.associateBy { it.companionId }
    val storedById = storedTargets.associateBy { it.companionId }
    val orderedIds = buildList {
        addAll(storedTargets.map { it.companionId })
        if (!removeMissingTargets) addAll(current.map { it.companionId }.filter { it !in storedById })
    }
    return orderedIds.mapNotNull { companionId ->
        val persistedTarget = storedById[companionId]
        val previous = currentById[companionId]
        when {
            persistedTarget != null -> toUiState(
                target = persistedTarget,
                availability = discoveryAvailability[companionId]
                    ?: previous?.availability
                    ?: KnownHostAvailability.UNKNOWN,
                selectedTargetId = selectedTargetId,
                reach = if (companionId in discoveryReach) discoveryReach[companionId] else previous?.reach,
            )

            !removeMissingTargets && previous != null -> previous.withActionEnabled(selectedTargetId)
            else -> null
        }
    }
}

internal fun KnownHostTarget.withDescriptor(descriptor: KnownHostDescriptor?): KnownHostTarget =
    descriptor?.let {
        copy(
            companionId = it.companionId,
            companionLabel = it.companionLabel,
            lastProjectName = it.projectName.takeIf(String::isNotBlank) ?: lastProjectName,
            hostVersion = it.hostVersion,
            lastUsedAtEpochMillis = lastUsedAtEpochMillis,
        )
    } ?: this

/** A host that is not answering has no reach to show, whatever answered before. */
internal fun KnownHostAnswer?.toUiReach(): KnownHostReach? =
    this?.takeIf { it.response.status.toUiAvailability() != KnownHostAvailability.UNAVAILABLE }?.reach

private fun String.hostOfEndpoint(): String =
    substringAfter("://").substringBefore('/').substringBefore(':')

internal fun DiscoveryStatus?.toUiAvailability(): KnownHostAvailability =
    when (this) {
        DiscoveryStatus.AVAILABLE -> KnownHostAvailability.AVAILABLE
        DiscoveryStatus.BLOCKED -> KnownHostAvailability.BLOCKED
        DiscoveryStatus.UNAVAILABLE, DiscoveryStatus.PAIR_REVOKED, null ->
            KnownHostAvailability.UNAVAILABLE
    }
