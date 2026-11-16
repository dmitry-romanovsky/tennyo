package app.tennyo.host.engine.transport

import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.OverlayEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The Node twin of the IntelliJ `LinkServerService`: the one externally reachable listener, shared by every holder of
 * a lease and torn down when the last one is released. Like the IntelliJ service it binds the same port on every
 * address [selectListenAddresses] picks, so a phone on any of the host's networks reaches the same session, and it
 * re-evaluates them every [RECONCILE_INTERVAL_SECONDS] seconds and whenever [reconcile] is called.
 *
 * [bindHost] maps a selected address to the one actually bound, so a test can listen on loopback while the endpoints
 * name the address under test.
 */
class NodeLinkServer(
    private val scope: CoroutineScope,
    private val identity: NodeTransportIdentity = loadOrCreateNodeTransportIdentity(),
    private val discoveryRoutes: DiscoveryRoutes? = null,
    private val interfaces: () -> List<InterfaceAddress> = ::nodeInterfaceAddresses,
    private val remoteAccess: () -> Boolean = { false },
    private val bindHost: (String) -> String = { it },
) {
    private val leasePolicy = ListenerLeasePolicy()
    private val bound = linkedMapOf<ListenAddress, NodeLinkListener>()
    private var boundPort: Int? = null
    private var reconciliation: Job? = null

    val isListening: Boolean get() = boundPort != null

    /**
     * Binds the listener if it is not up yet and hands out a lease on it. [onReady] is told where the phone can reach
     * this host; binding is asynchronous on Node, so the endpoints are not available when this returns.
     */
    fun acquire(preferredPort: Int?, ownerKind: String, onReady: (Result<NodeListenerLease>) -> Unit) {
        acquireIfListening(ownerKind)?.let {
            onReady(Result.success(it))
            return
        }
        val leaseId = leasePolicy.acquire(ownerKind)
        val release = { release(leaseId) }
        val selected = selectListenAddresses(interfaces(), remoteAccess())
        if (selected.isEmpty()) {
            release()
            onReady(Result.failure(IllegalStateException("No eligible private IPv4 network interface is available.")))
            return
        }
        val ports = listOfNotNull(preferredPort) + PORT_RANGE.toList() + ANY_PORT
        bindAll(selected, ports) { result ->
            onReady(result.map { NodeListenerLease(::requireEndpoints, release) }.onFailure { release() })
        }
    }

    /**
     * A lease on a listener that is already bound, and `null` while it is still coming up. A host whose protocol
     * platform must answer at once starts the listener during activation and takes its leases through this.
     */
    fun acquireIfListening(ownerKind: String): NodeListenerLease? {
        if (boundPort == null) return null
        val leaseId = leasePolicy.acquire(ownerKind)
        return NodeListenerLease(::requireEndpoints) { release(leaseId) }
    }

    /**
     * Brings the bound sockets in line with [selectListenAddresses]: it closes the ones whose address left the
     * selection and binds new ones on the port the listener already holds.
     */
    fun reconcile() {
        val port = boundPort ?: return
        val selected = selectListenAddresses(interfaces(), remoteAccess())
        if (!remoteAccess()) bound.values.forEach(NodeLinkListener::closeRemoteConnections)
        bound.keys.filter { it !in selected }.forEach { address -> bound.remove(address)?.close() }
        selected.filter { it !in bound }.forEach { address ->
            bind(address, port, onFailed = { reason -> LOG.warn("Unable to bind ${address.address}: $reason") }) {}
        }
    }

    /** Where the listener answers right now, or `null` while it is down or has no LAN address. */
    fun endpoints(): HostEndpoints? {
        val port = boundPort ?: return null
        val addresses = bound.keys.sortedBy { it.kind }
        val lan = addresses.filter { it.kind == ListenKind.LAN }.map { urlOf(it.address, port) }
        val primary = lan.firstOrNull() ?: return null
        return HostEndpoints(
            primary = primary,
            alternates = lan.drop(1),
            transportFingerprint = identity.fingerprint,
            port = port,
            overlay = addresses.filter { it.kind == ListenKind.OVERLAY }.map {
                OverlayEndpoint(checkNotNull(it.network), it.address, urlOf(it.address, port))
            },
        )
    }

    private fun requireEndpoints(): HostEndpoints =
        endpoints() ?: error("No private IPv4 address is available for the link.")

    /**
     * Binds the first address on the first free port of [ports] — another editor window or IDE may hold one — then
     * binds the rest to that same port.
     */
    private fun bindAll(selected: List<ListenAddress>, ports: List<Int>, onBound: (Result<Int>) -> Unit) {
        val port = ports.firstOrNull() ?: return onBound(Result.failure(IllegalStateException("No free port.")))
        val onFailed = { reason: String ->
            if (ports.size == 1) {
                onBound(Result.failure(IllegalStateException("The listener could not bind: $reason")))
            } else {
                bindAll(selected, ports.drop(1), onBound)
            }
        }
        bind(selected.first(), port, onFailed) { selectedPort ->
            selected.drop(1).forEach { bind(it, selectedPort, onFailed = {}) {} }
            boundPort = selectedPort
            reconciliation = scope.launch {
                while (isActive) {
                    delay(RECONCILE_INTERVAL_SECONDS.seconds)
                    reconcile()
                }
            }
            onBound(Result.success(selectedPort))
        }
    }

    private fun bind(address: ListenAddress, port: Int, onFailed: (String) -> Unit, onBound: (Int) -> Unit) {
        val gate = OriginGate(address, remoteAccess, ownAddresses = { interfaces().map { it.address }.toSet() })
        val listener = NodeLinkListener(identity, scope, gate, discoveryRoutes = discoveryRoutes)
        bound[address] = listener
        listener.listen(
            host = bindHost(address.address),
            port = port,
            onFailed = { reason ->
                bound.remove(address)
                listener.close()
                onFailed(reason)
            },
            onBound = onBound,
        )
    }

    /** Drops every lease and takes the listener down, for a host shutting the whole companion off. */
    fun close() {
        leasePolicy.clear()
        shutDown()
    }

    private fun release(leaseId: String) {
        if (!leasePolicy.release(leaseId)) return
        if (leasePolicy.isListenerRequired) return
        shutDown()
    }

    private fun shutDown() {
        reconciliation?.cancel()
        reconciliation = null
        bound.values.forEach { it.close() }
        bound.clear()
        boundPort = null
    }

    private fun urlOf(address: String, port: Int) = "https://$address:$port${LinkEndpoints.PREFIX}"

    private companion object {
        /** The range the IntelliJ listener tries, so a phone's saved port keeps working; then any free port. */
        val PORT_RANGE = 63_343..63_383
        const val ANY_PORT = 0
        const val RECONCILE_INTERVAL_SECONDS = 30
        val LOG = HostLogger("NodeLinkServer")
    }
}

/**
 * One holder's claim on the shared listener; [release] is idempotent. [endpoints] is read when needed, because the
 * listener's addresses change while a lease is held.
 */
class NodeListenerLease(val endpoints: () -> HostEndpoints, private val onRelease: () -> Unit) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/** The host's IPv4 addresses with the subnet size of each, the one thing the listener's address rules read. */
fun nodeInterfaceAddresses(): List<InterfaceAddress> = interfaceAddressesIn(NodeOs.networkInterfaces())

/** [nodeInterfaceAddresses] over a given `os.networkInterfaces()` map, which is what the parsing is tested on. */
internal fun interfaceAddressesIn(byInterface: dynamic): List<InterfaceAddress> {
    val found = mutableListOf<InterfaceAddress>()
    for (name in js("Object.keys")(byInterface) as Array<String>) {
        val entries = byInterface[name]
        val count = (entries?.length as? Int) ?: 0
        for (index in 0 until count) {
            interfaceAddressOf(name, entries[index])?.let(found::add)
        }
    }
    return found
}

/** The entry as an address when it is an external IPv4 one, and `null` for anything else. */
private fun interfaceAddressOf(name: String, entry: dynamic): InterfaceAddress? {
    val family = entry.family
    if (family != IPV4_NAME && family != IPV4_NUMBER) return null
    if (entry.internal == true) return null
    val address = entry.address as? String ?: return null
    val prefix = (entry.cidr as? String)?.substringAfter('/')?.toIntOrNull() ?: 0
    return InterfaceAddress(interfaceName = name, address = address, prefixLength = prefix)
}

private const val IPV4_NAME = "IPv4"
private const val IPV4_NUMBER = 4
