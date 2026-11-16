package app.tennyo.mobile.feature.connect

import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.MAX_ADDED_ENDPOINTS
import app.tennyo.mobile.link.pairing.RemoteAddressParse

/** Every state change of the connect screen, grouped by the part of the screen it touches. */
internal sealed interface ConnectMsg {
    sealed interface Discovery : ConnectMsg {
        data class KnownHostsLoaded(val rows: List<KnownHostTargetUiState>) : Discovery
        data class Finished(val rows: List<KnownHostTargetUiState>) : Discovery
        data object ErrorDismissed : Discovery
    }

    /** The hosts in pairing mode, as one pass of [PairingDiscoveryLoop] leaves them. */
    sealed interface Pairing : ConnectMsg {
        data class HostsProbed(val rows: List<PairingHostUiState>) : Pairing
        data class HostSelected(val shortId: String?) : Pairing
    }

    sealed interface KnownHost : ConnectMsg {
        data class Selected(val targetId: String) : KnownHost
        data class ProjectSelectionRequested(val selection: ProjectSelectionUiState) : KnownHost
        data object SessionStartStarted : KnownHost

        /** Ends a remembered-host attempt: clears the selection and, when it failed, shows [failure]. */
        data class SessionStartEnded(val failure: ConnectErrorUiState?) : KnownHost
        data class Forgotten(val targetId: String) : KnownHost
    }

    /** The addresses sheet of a known host. A late [Checked] for a sheet that has since closed is ignored. */
    sealed interface Addresses : ConnectMsg {
        data class Opened(val targetId: String, val learned: List<String>, val added: List<String>) : Addresses
        data class InputChanged(val text: String) : Addresses

        /** The user submitted the input; a valid address joins [KnownHostAddressesUiState.added] and is checked. */
        data class Added(val parse: RemoteAddressParse) : Addresses
        data class Checked(val targetId: String, val endpoint: String, val result: AddressCheck) : Addresses
        data class Removed(val endpoint: String) : Addresses
        data object Closed : Addresses
    }

    sealed interface Permission : ConnectMsg {
        data class NotificationChanged(val isGranted: Boolean) : Permission
        data class NotificationExplanationShown(val visible: Boolean) : Permission
    }
}

internal fun reduceConnect(state: ConnectState, msg: ConnectMsg): ConnectState = when (msg) {
    is ConnectMsg.Discovery -> reduceDiscovery(state, msg)
    is ConnectMsg.Pairing -> reducePairing(state, msg)
    is ConnectMsg.KnownHost -> reduceKnownHost(state, msg)
    is ConnectMsg.Addresses -> state.copy(addresses = reduceAddresses(state.addresses, msg))
    is ConnectMsg.Permission -> reducePermission(state, msg)
}

private fun reduceAddresses(sheet: KnownHostAddressesUiState?, msg: ConnectMsg.Addresses): KnownHostAddressesUiState? =
    when (msg) {
        is ConnectMsg.Addresses.Opened -> KnownHostAddressesUiState(msg.targetId, msg.learned, msg.added)
        ConnectMsg.Addresses.Closed -> null
        is ConnectMsg.Addresses.InputChanged -> sheet?.copy(input = msg.text, inputError = null)
        is ConnectMsg.Addresses.Added -> sheet?.let { addAddress(it, msg.parse) }
        is ConnectMsg.Addresses.Checked ->
            if (sheet != null && sheet.targetId == msg.targetId && msg.endpoint in sheet.added) {
                sheet.copy(
                    check = AddressCheckUiState(msg.endpoint, msg.result),
                    checking = sheet.checking - msg.endpoint,
                )
            } else {
                sheet
            }

        is ConnectMsg.Addresses.Removed -> sheet?.copy(
            added = sheet.added - msg.endpoint,
            checking = sheet.checking - msg.endpoint,
            check = sheet.check?.takeUnless { it.endpoint == msg.endpoint },
        )
    }

private fun addAddress(sheet: KnownHostAddressesUiState, parse: RemoteAddressParse): KnownHostAddressesUiState =
    when (parse) {
        is RemoteAddressParse.Invalid -> sheet.copy(inputError = parse.error)
        is RemoteAddressParse.Valid ->
            if (sheet.lists(parse.endpoint)) {
                sheet.copy(input = "", inputError = null)
            } else {
                sheet.copy(
                    added = (sheet.added + parse.endpoint).takeLast(MAX_ADDED_ENDPOINTS),
                    input = "",
                    inputError = null,
                    check = null,
                    checking = sheet.checking + parse.endpoint,
                )
            }
    }

private fun reducePairing(state: ConnectState, msg: ConnectMsg.Pairing): ConnectState = when (msg) {
    // The loop already decided which rows survive this pass; the reducer only records them.
    is ConnectMsg.Pairing.HostsProbed -> state.copy(
        pairingHosts = msg.rows.sortedBy { it.hostName },
        selectedPairingHostId = state.selectedPairingHostId
            ?.takeIf { selected -> msg.rows.any { it.shortId == selected } },
    )

    is ConnectMsg.Pairing.HostSelected -> state.copy(selectedPairingHostId = msg.shortId, discoveryError = null)
}

private fun reduceDiscovery(state: ConnectState, msg: ConnectMsg.Discovery): ConnectState = when (msg) {
    is ConnectMsg.Discovery.KnownHostsLoaded -> state.copy(knownHostTargets = msg.rows)
    is ConnectMsg.Discovery.Finished -> state.copy(knownHostTargets = msg.rows)
    ConnectMsg.Discovery.ErrorDismissed -> state.copy(discoveryError = null)
}

private fun reduceKnownHost(state: ConnectState, msg: ConnectMsg.KnownHost): ConnectState = when (msg) {
    is ConnectMsg.KnownHost.Selected -> state.copy(
        selectedDiscoveryTargetId = msg.targetId,
        projectSelection = null,
        discoveryError = null,
        knownHostTargets = state.knownHostTargets.withActionsFor(msg.targetId),
    )

    is ConnectMsg.KnownHost.ProjectSelectionRequested -> state.copy(
        projectSelection = msg.selection,
        knownHostTargets = state.knownHostTargets.withActionsFor(msg.selection.targetId),
    )

    ConnectMsg.KnownHost.SessionStartStarted -> state.copy(isStartingDiscoverySession = true)
    is ConnectMsg.KnownHost.SessionStartEnded -> state.copy(
        isStartingDiscoverySession = false,
        selectedDiscoveryTargetId = null,
        projectSelection = null,
        discoveryError = msg.failure ?: state.discoveryError,
        knownHostTargets = state.knownHostTargets.withActionsFor(selectedTargetId = null),
    )

    is ConnectMsg.KnownHost.Forgotten -> {
        val forgettingActiveTarget =
            state.selectedDiscoveryTargetId == msg.targetId || state.projectSelection?.targetId == msg.targetId
        val selectedTargetId = state.selectedDiscoveryTargetId.takeUnless { it == msg.targetId }
        state.copy(
            selectedDiscoveryTargetId = selectedTargetId,
            projectSelection = state.projectSelection?.takeUnless { it.targetId == msg.targetId },
            discoveryError = state.discoveryError.takeUnless { forgettingActiveTarget },
            knownHostTargets = state.knownHostTargets
                .filterNot { it.companionId == msg.targetId }
                .withActionsFor(selectedTargetId),
        )
    }
}

private fun reducePermission(state: ConnectState, msg: ConnectMsg.Permission): ConnectState = when (msg) {
    is ConnectMsg.Permission.NotificationChanged -> state.copy(
        notificationPermission = state.notificationPermission.copy(
            isGranted = msg.isGranted,
            explanationVisible = state.notificationPermission.explanationVisible && !msg.isGranted,
        ),
    )

    is ConnectMsg.Permission.NotificationExplanationShown ->
        state.copy(notificationPermission = state.notificationPermission.copy(explanationVisible = msg.visible))
}

/** Only an available IDE can be tapped, and while one attempt runs every other row is disabled. */
internal fun KnownHostTargetUiState.withActionEnabled(selectedTargetId: String?): KnownHostTargetUiState =
    copy(
        actionEnabled = availability == KnownHostAvailability.AVAILABLE &&
            (selectedTargetId == null || selectedTargetId == companionId),
    )

private fun List<KnownHostTargetUiState>.withActionsFor(selectedTargetId: String?) =
    map { it.withActionEnabled(selectedTargetId) }
