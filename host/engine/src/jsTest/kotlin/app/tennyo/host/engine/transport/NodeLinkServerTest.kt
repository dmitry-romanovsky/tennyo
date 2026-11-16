package app.tennyo.host.engine.transport

import app.tennyo.host.engine.platform.jsOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The shared listener's lifetime, the addresses it is offered on, and who it lets connect at all. */
class NodeLinkServerTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val identity = newNodeTransportIdentity()
    private val wifi = InterfaceAddress("Wi-Fi", LAN_ADDRESS, 24)
    private val tailscale = InterfaceAddress("Tailscale", OVERLAY_ADDRESS, 32)
    private var remoteAccess = false
    private var interfaces = listOf(wifi)

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `the listener stays up until the last lease is released`() = runTest {
        withContext(Dispatchers.Default) {
            val server = newServer()

            val first = server.acquire()
            val second = server.acquire()
            assertTrue(server.isListening)
            assertEquals(first.endpoints().primary, second.endpoints().primary, "the two leases got different listeners")

            first.release()
            assertTrue(server.isListening, "the listener went down while a lease was still held")
            first.release()
            assertTrue(server.isListening, "releasing the same lease twice took the listener down")

            second.release()
            assertFalse(server.isListening, "the listener stayed up with no lease held")
        }
    }

    @Test
    fun `a port another listener holds is skipped instead of crashing the host`() = runTest {
        withContext(Dispatchers.Default) {
            val holder = newServer()
            val held = holder.acquire()
            val heldPort = held.endpoints().primary.substringAfterLast(':').substringBefore('/').toInt()

            val second = newServer()
            val lease = second.acquire(preferredPort = heldPort)

            assertTrue(second.isListening)
            assertTrue(!lease.endpoints().primary.contains(":$heldPort/"), "the second listener reused a held port")
            lease.release()
            held.release()
        }
    }

    @Test
    fun `the endpoints name the LAN address the listener is bound for, and its certificate`() = runTest {
        withContext(Dispatchers.Default) {
            val server = newServer()

            val lease = server.acquire()

            val endpoints = lease.endpoints()
            assertTrue(
                endpoints.primary.startsWith("https://$LAN_ADDRESS:") && endpoints.primary.endsWith("/api/tennyo"),
                "unexpected primary endpoint ${endpoints.primary}",
            )
            assertEquals(identity.fingerprint, endpoints.transportFingerprint)
            lease.release()
        }
    }

    @Test
    fun `a host with no private address is refused a listener rather than offered a useless one`() = runTest {
        withContext(Dispatchers.Default) {
            interfaces = emptyList()
            val server = newServer()

            assertFailsWith<IllegalStateException> { server.acquire() }
            assertFalse(server.isListening)
        }
    }

    @Test
    fun `Node's interface map is read as IPv4 addresses with their subnet size`() {
        val interfaces = js("{}")
        interfaces["Wi-Fi"] = js("[]")
        interfaces["Wi-Fi"].push(js("{ address: '192.168.1.10', family: 'IPv4', internal: false, cidr: '192.168.1.10/24' }"))
        interfaces["Wi-Fi"].push(js("{ address: 'fe80::1', family: 'IPv6', internal: false, cidr: 'fe80::1/64' }"))
        interfaces["Loopback"] = js("[]")
        interfaces["Loopback"].push(js("{ address: '127.0.0.1', family: 'IPv4', internal: true, cidr: '127.0.0.1/8' }"))

        assertEquals(listOf(InterfaceAddress("Wi-Fi", "192.168.1.10", 24)), interfaceAddressesIn(interfaces))
    }

    @Test
    fun `remote access on binds the overlay address on the same port, and off closes it on the next reconcile`() =
        runTest {
            withContext(Dispatchers.Default) {
                interfaces = listOf(wifi, tailscale)
                remoteAccess = true
                val server = newServer()
                val lease = server.acquire()
                val port = lease.endpoints().port

                val endpoints = lease.endpoints()
                assertEquals(listOf("https://$OVERLAY_ADDRESS:$port/api/tennyo"), endpoints.overlay.map { it.url })
                assertEquals(OverlayNetwork.TAILNET_RANGE, endpoints.overlay.single().network)
                assertTrue(endpoints.primary.startsWith("https://$LAN_ADDRESS:"))
                assertTrue(endpoints.alternates.none { OVERLAY_ADDRESS in it }, "the overlay address leaked into alternates")
                eventually { listening(OVERLAY_BIND, port) }

                remoteAccess = false
                server.reconcile()

                assertEquals(emptyList(), lease.endpoints().overlay)
                eventually { !listening(OVERLAY_BIND, port) }
                lease.release()
            }
        }

    @Test
    fun `an overlay address that appears after the listener started is bound by reconcile`() = runTest {
        withContext(Dispatchers.Default) {
            remoteAccess = true
            val server = newServer()
            val lease = server.acquire()
            assertEquals(emptyList(), lease.endpoints().overlay)

            interfaces = listOf(wifi, tailscale)
            server.reconcile()

            assertEquals(listOf(OVERLAY_ADDRESS), lease.endpoints().overlay.map { it.address })
            eventually { listening(OVERLAY_BIND, lease.endpoints().port) }
            lease.release()
        }
    }

    @Test
    fun `a remote source is closed before TLS while remote access is off`() = runTest {
        withContext(Dispatchers.Default) {
            val server = newServer()
            val lease = server.acquire()

            // The test connects from loopback, which is never a local origin.
            assertFalse(handshakeCompletes(lease.endpoints().port), "a TLS handshake was served to a remote source")
            lease.release()
        }
    }

    @Test
    fun `a remote source is served once remote access is on`() = runTest {
        withContext(Dispatchers.Default) {
            remoteAccess = true
            val server = newServer()
            val lease = server.acquire()

            assertTrue(handshakeCompletes(lease.endpoints().port))
            lease.release()
        }
    }

    @Test
    fun `an open remote connection is closed when remote access turns off`() = runTest {
        withContext(Dispatchers.Default) {
            remoteAccess = true
            val server = newServer()
            val lease = server.acquire()
            val open = openTls(lease.endpoints().port)

            remoteAccess = false
            server.reconcile()

            withTimeout(TIMEOUT) { open.closed.await() }
            lease.release()
        }
    }

    @Test
    fun `connections on an address that leaves the selection are closed with it`() = runTest {
        withContext(Dispatchers.Default) {
            remoteAccess = true
            interfaces = listOf(wifi, InterfaceAddress("Ethernet", SECOND_LAN_ADDRESS, 24))
            val server = newServer()
            val lease = server.acquire()
            val open = openTls(lease.endpoints().port, SECOND_LAN_BIND)
            assertFalse(open.closed.isCompleted)

            interfaces = listOf(wifi)
            server.reconcile()

            withTimeout(TIMEOUT) { open.closed.await() }
            lease.release()
        }
    }

    private class OpenTls(val closed: CompletableDeferred<Unit>)

    /** A finished TLS connection from loopback, which the listener sees as a remote origin. */
    private suspend fun openTls(port: Int, host: String = LOOPBACK): OpenTls {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val socket = RawTls.connect(jsOptions("port" to port, "host" to host, "rejectUnauthorized" to false)) {
            connected.complete(Unit)
        }
        socket.on("close") { closed.complete(Unit) }
        socket.on("error") { }
        withTimeout(TIMEOUT) { connected.await() }
        return OpenTls(closed)
    }

    private fun newServer() = NodeLinkServer(
        scope,
        identity,
        interfaces = { interfaces },
        remoteAccess = { remoteAccess },
        bindHost = {
            when (it) {
                OVERLAY_ADDRESS -> OVERLAY_BIND
                SECOND_LAN_ADDRESS -> SECOND_LAN_BIND
                else -> LOOPBACK
            }
        },
    )

    private suspend fun NodeLinkServer.acquire(preferredPort: Int = 0): NodeListenerLease {
        val ready = CompletableDeferred<Result<NodeListenerLease>>()
        acquire(preferredPort = preferredPort, ownerKind = "test") { ready.complete(it) }
        return withTimeout(TIMEOUT) { ready.await() }.getOrThrow()
    }

    /** Whether a TCP connection to [host]:[port] is accepted. */
    private suspend fun listening(host: String, port: Int): Boolean {
        val outcome = CompletableDeferred<Boolean>()
        val socket = RawNet.connect(jsOptions("port" to port, "host" to host)) { outcome.complete(true) }
        socket.on("error") { outcome.complete(false) }
        return withTimeout(TIMEOUT) { outcome.await() }.also { socket.destroy() }
    }

    /** Whether a TLS client gets as far as a finished handshake; a refused one is hung up on instead. */
    private suspend fun handshakeCompletes(port: Int): Boolean {
        val outcome = CompletableDeferred<Boolean>()
        val socket = RawTls.connect(
            jsOptions("port" to port, "host" to LOOPBACK, "rejectUnauthorized" to false),
        ) { outcome.complete(true) }
        socket.on("close") { outcome.complete(false) }
        socket.on("error") { outcome.complete(false) }
        return withTimeout(TIMEOUT) { outcome.await() }.also { socket.destroy() }
    }

    private suspend fun eventually(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT) {
            while (!condition()) delay(POLL)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LAN_ADDRESS = "192.168.1.10"
        const val OVERLAY_ADDRESS = "100.101.2.3"
        const val SECOND_LAN_ADDRESS = "192.168.1.11"
        const val SECOND_LAN_BIND = "127.0.0.3"

        /** A second loopback address, so the overlay socket can share the LAN socket's port. */
        const val OVERLAY_BIND = "127.0.0.2"
        val TIMEOUT = 10.seconds
        val POLL = 20.milliseconds
    }
}

@JsModule("node:tls")
@JsNonModule
private external object RawTls {
    fun connect(options: dynamic, onConnected: () -> Unit): NodeSocket
}

@JsModule("node:net")
@JsNonModule
private external object RawNet {
    fun connect(options: dynamic, onConnected: () -> Unit): NodeSocket
}
