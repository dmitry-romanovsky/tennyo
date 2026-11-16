package app.tennyo.mobile.feature.connect

import app.tennyo.contract.ProjectChoice
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.connect_discovery_session_failed
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_unavailable
import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.KnownHostReach
import app.tennyo.mobile.link.pairing.RemoteAddressError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

interface ConnectComponent {
    val state: StateFlow<ConnectState>
    val effects: Flow<ConnectEffect>

    fun onIntent(intent: ConnectIntent)
    fun onBack()
}

/**
 * The pairing screen: the hosts this device remembers, and the hosts in pairing mode nearby. Both lists are kept
 * current by [PairingDiscoveryLoop] while the screen is resumed — there is no refresh gesture and no countdown.
 */
data class ConnectState(
    val knownHostTargets: List<KnownHostTargetUiState> = emptyList(),
    val pairingHosts: List<PairingHostUiState> = emptyList(),
    val selectedDiscoveryTargetId: String? = null,
    val selectedPairingHostId: String? = null,
    val projectSelection: ProjectSelectionUiState? = null,
    val addresses: KnownHostAddressesUiState? = null,
    val isStartingDiscoverySession: Boolean = false,
    val discoveryError: ConnectErrorUiState? = null,
    val notificationPermission: NotificationPermissionState = NotificationPermissionState(),
) {
    /**
     * The pairing rows, less the hosts this phone already knows and can reach: their known row connects with the
     * remembered pair, while pairing would ask the desktop to accept the phone and the phone for the words again.
     */
    val visiblePairingHosts: List<PairingHostUiState>
        get() {
            val reachable = knownHostTargets
                .filter { it.availability == KnownHostAvailability.AVAILABLE }
                .mapTo(mutableSetOf()) { it.displayShortId }
            return pairingHosts.filterNot { it.displayShortId in reachable }
        }
}

/**
 * One host advertising an open pairing window. It carries no project and no capabilities: a host says that much
 * only after the user approves this device on the desktop.
 */
data class PairingHostUiState(
    /** The fingerprint-derived id, as the identity the row is keyed by. */
    val shortId: String,
    /** [shortId] grouped for reading, as the desktop shows it. */
    val displayShortId: String,
    val hostName: String,
    val hostLabel: String,
    val endpoint: String,
    val expiresAtEpochMillis: Long,
    val isBlocked: Boolean = false,
)

/** A remembered-host attempt that failed, with the host's or the transport's own account behind "Details". */
data class ConnectErrorUiState(
    val failure: ConnectFailure,
    val details: UiText? = null,
)

enum class ConnectFailure(val text: StringResource) {
    KnownHostUnavailable(Res.string.connect_known_host_unavailable),
    SessionStartFailed(Res.string.connect_discovery_session_failed),
}

data class KnownHostTargetUiState(
    val companionId: String,
    val companionLabel: String,
    /** Grouped for reading, as the pairing rows and the desktop show it. */
    val displayShortId: String,
    val lastProjectName: String?,
    val hostVersion: String,
    val lastUsedAtEpochMillis: Long,

    /** The network the host was last reached on; null when only the local network is known. */
    val networkLabel: String?,
    val availability: KnownHostAvailability,
    val actionEnabled: Boolean,

    /** How the last pass reached the host; null while it is not answering. */
    val reach: KnownHostReach? = null,

    /** The host part of the address that last answered, shown beside an away host. */
    val resolvedAddress: String? = null,
)

/** The addresses sheet of one known host: what it learned from tickets, what the user typed, and the last check. */
data class KnownHostAddressesUiState(
    val targetId: String,
    val learned: List<String>,
    val added: List<String>,
    val input: String = "",
    val inputError: RemoteAddressError? = null,
    val check: AddressCheckUiState? = null,
    /** The typed addresses still waiting for their check to answer. */
    val checking: Set<String> = emptySet(),
) {
    val isChecking: Boolean get() = checking.isNotEmpty()

    /** Whether [endpoint] is already listed, whether the host taught it or the user typed it. */
    fun lists(endpoint: String): Boolean =
        (learned + added).any { it.authority() == endpoint.authority() }
}

private fun String.authority(): String = substringAfter("://").substringBefore('/')

data class AddressCheckUiState(val endpoint: String, val result: AddressCheck)

data class ProjectSelectionUiState(
    val targetId: String,
    val companionLabel: String,
    val projects: List<ProjectChoiceUiState>,
)

data class ProjectChoiceUiState(
    val projectId: String,
    val projectName: String,
    val projectPath: String?,
)

enum class KnownHostAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    BLOCKED,
}

data class NotificationPermissionState(
    val isGranted: Boolean = true,
    val explanationVisible: Boolean = false,
)

data class NotificationPermissionStatus(
    val isGranted: Boolean,
)

sealed interface ConnectIntent {
    /** A host in pairing mode was tapped, named by its short id. */
    data class PairingHostSelected(val shortId: String) : ConnectIntent
    data class StartSessionAndConnectClicked(val targetId: String) : ConnectIntent
    data class ForgetKnownHostClicked(val targetId: String) : ConnectIntent
    data class ProjectSelectionConfirmed(val targetId: String, val projectId: String) : ConnectIntent
    data object ProjectSelectionDismissed : ConnectIntent
    data class AddressesClicked(val targetId: String) : ConnectIntent
    data class AddressInputChanged(val text: String) : ConnectIntent
    data object AddressSubmitted : ConnectIntent
    data class AddressRemoveClicked(val endpoint: String) : ConnectIntent
    data object AddressesDismissed : ConnectIntent
    data object DiscoveryErrorDismissed : ConnectIntent
    data class NotificationPermissionStatusLoaded(
        val status: NotificationPermissionStatus,
    ) : ConnectIntent

    data object NotificationPermissionInfoClicked : ConnectIntent
    data object NotificationPermissionRequestDismissed : ConnectIntent
    data object NotificationPermissionRequestConfirmed : ConnectIntent
    data class NotificationPermissionRequestResult(val isGranted: Boolean) : ConnectIntent
}

sealed interface ConnectEffect {
    data object RequestNotificationPermission : ConnectEffect
}

internal fun ProjectChoice.toUiState(): ProjectChoiceUiState =
    ProjectChoiceUiState(
        projectId = projectId,
        projectName = projectName,
        projectPath = projectPath,
    )
