package app.tennyo.host.engine.discovery

import app.tennyo.contract.Discovery
import app.tennyo.contract.ProtocolVersions
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.transport.nodeInterfaceAddresses
import app.tennyo.host.engine.transport.selectListenAddresses

/**
 * DNS-SD on Node, the twin of the IntelliJ jmdns advertiser: one `_tennyo-link._tcp` service named after the
 * companion id, with the same `v` and `p` TXT hints. The phone browses for it and then probes the address it resolves.
 *
 * One responder runs per LAN address the link is offered on, bound to that interface. A single default responder sends
 * on whatever interface carries the default route, which with a VPN up is the tunnel, not the Wi-Fi the phone is on.
 *
 * `probe` is off because every window of the editor advertises the same companion id: probing would rename the
 * second one to "id (2)", which a phone matching on the id never finds. Any window answers for the shared pair store.
 */
class NodeDnsSdAdvertiser(
    private val addresses: () -> List<String> = ::lanAddresses,
) : DiscoveryAdvertiser {
    private var responders: List<dynamic> = emptyList()
    private var current: String? = null

    override fun advertise(companionId: String, endpoint: String, pairingWindowOpen: Boolean) {
        val port = endpointPort(endpoint) ?: return
        val interfaces = addresses()
        val key = "$companionId|$port|$pairingWindowOpen|$interfaces"
        if (key == current) return
        stop()
        val txt = jsOptions(
            Discovery.TXT_SECURITY_VERSION to ProtocolVersions.SECURITY_VERSION.toString(),
            Discovery.TXT_PAIRING_WINDOW to if (pairingWindowOpen) "1" else "0",
        )
        responders = interfaces.mapNotNull { address -> publish(address, companionId, port, txt) }
        current = key
        LOG.info("Advertising $SERVICE_TYPE on port $port via $interfaces (pairing window open: $pairingWindowOpen).")
    }

    override fun unadvertise(companionId: String) {
        if (responders.isNotEmpty()) LOG.info("Stopped advertising $SERVICE_TYPE.")
        stop()
    }

    private fun publish(address: String, companionId: String, port: Int, txt: dynamic): dynamic = runCatching {
        val bonjourClass = js("require('bonjour-service').Bonjour")
        val onError = { error: dynamic -> LOG.warn("DNS-SD on $address failed: ${error?.message}") }
        val bonjour = js("new bonjourClass({ interface: address }, onError)")
        // The responder's socket reports a bind failure here, not through the service.
        bonjour.server.mdns.on("error", onError)
        val service = bonjour.publish(
            jsOptions(
                "name" to companionId,
                "type" to SERVICE_TYPE,
                "protocol" to "tcp",
                "port" to port,
                "txt" to txt,
                "probe" to false,
            ),
        )
        service.on("error", onError)
        bonjour
    }.onFailure { LOG.warn("DNS-SD advertisement on $address could not start: ${it.message}") }.getOrNull()

    private fun stop() {
        responders.forEach { runCatching { it.unpublishAll(); it.destroy() } }
        responders = emptyList()
        current = null
    }

    private companion object {
        /** `_tennyo-link._tcp` as bonjour-service names it: the type without its underscore and protocol. */
        val SERVICE_TYPE = Discovery.SERVICE_TYPE.removePrefix("_").substringBefore("._")
        val LOG = HostLogger("NodeDnsSdAdvertiser")
    }
}

/** The LAN addresses DNS-SD announces on; an overlay address is reached by a ticket, never advertised. */
private fun lanAddresses(): List<String> =
    selectListenAddresses(nodeInterfaceAddresses(), remoteAccess = false).map { it.address }

/** The port of a link endpoint URL, or `null` when it names none. */
internal fun endpointPort(endpoint: String): Int? =
    Regex("^https?://[^/:]+:([0-9]+)").find(endpoint)?.groupValues?.get(1)?.toIntOrNull()
