package app.tennyo.mobile.feature.connect

import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.KnownHostTarget
import app.tennyo.mobile.link.pairing.RemoteAddress
import app.tennyo.mobile.link.pairing.RemoteAddressParse
import app.tennyo.mobile.link.store.FeatureStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The addresses sheet's work: reading a host's addresses, and saving and checking the ones the user types. */
internal class KnownHostAddresses(
    private val store: FeatureStore<ConnectState, ConnectMsg, ConnectEffect>,
    private val discovery: KnownHostDiscovery,
    private val backgroundDispatcher: CoroutineDispatcher,
) {
    fun open(targetId: String) {
        store.scope.launch {
            val target = target(targetId) ?: return@launch
            val learned = (listOfNotNull(target.lastResolvedHost) + target.ticketEndpoints).distinct()
            store.dispatch(
                ConnectMsg.Addresses.Opened(
                    targetId = targetId,
                    learned = learned - target.addedEndpoints.toSet(),
                    added = target.addedEndpoints,
                ),
            )
        }
    }

    fun submit() {
        val sheet = store.state.value.addresses ?: return
        store.scope.launch {
            val target = target(sheet.targetId) ?: return@launch
            val parse = RemoteAddress.parse(sheet.input, defaultPort(target))
            store.dispatch(ConnectMsg.Addresses.Added(parse))
            if (parse !is RemoteAddressParse.Valid || sheet.lists(parse.endpoint)) return@launch
            withContext(backgroundDispatcher) { discovery.addAddress(target.companionId, parse.endpoint) }
            val result = runCatching { discovery.checkAddress(target, parse.endpoint) }
                .getOrDefault(AddressCheck.NO_ANSWER)
            store.dispatch(ConnectMsg.Addresses.Checked(target.companionId, parse.endpoint, result))
        }
    }

    fun remove(endpoint: String) {
        val targetId = store.state.value.addresses?.targetId ?: return
        store.dispatch(ConnectMsg.Addresses.Removed(endpoint))
        store.scope.launch { withContext(backgroundDispatcher) { discovery.removeAddress(targetId, endpoint) } }
    }

    private suspend fun target(targetId: String): KnownHostTarget? =
        withContext(backgroundDispatcher) { discovery.knownHostTargets().firstOrNull { it.companionId == targetId } }

    private fun defaultPort(target: KnownHostTarget): Int =
        target.ticketEndpoints.firstNotNullOfOrNull(RemoteAddress::portOf) ?: RemoteAddress.DEFAULT_PORT
}
