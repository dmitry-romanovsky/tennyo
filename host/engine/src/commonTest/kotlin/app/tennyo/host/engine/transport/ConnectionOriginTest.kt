package app.tennyo.host.engine.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionOriginTest {
    private val lan = ListenAddress("192.168.1.10", ListenKind.LAN, null, prefixLength = 24)
    private val overlay = ListenAddress("100.101.2.1", ListenKind.OVERLAY, OverlayNetwork.TAILNET_RANGE, 10)
    private val own = setOf("192.168.1.10", "100.101.2.1")

    @Test
    fun `a private source on the same subnet of a LAN socket is local`() {
        assertEquals(ConnectionOrigin.LOCAL, connectionOrigin("192.168.1.20", lan, own))
    }

    @Test
    fun `a public source on a LAN socket is a port forward and remote`() {
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("203.0.113.5", lan, own))
    }

    @Test
    fun `the host's own address on a LAN socket is a local proxy and remote`() {
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("192.168.1.10", lan, own))
    }

    @Test
    fun `loopback is remote`() {
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("127.0.0.1", lan, own))
    }

    @Test
    fun `a private source outside the interface subnet is remote`() {
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("192.168.2.20", lan, own))
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("10.0.0.7", lan, own))
    }

    @Test
    fun `an overlay socket is remote whatever the source`() {
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("100.101.2.3", overlay, own))
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("10.147.17.5", overlay, own))
        assertEquals(ConnectionOrigin.REMOTE, connectionOrigin("192.168.1.20", overlay, own))
    }

    @Test
    fun `an IPv4-mapped IPv6 source is read as its IPv4 address`() {
        assertEquals(ConnectionOrigin.LOCAL, connectionOrigin("::ffff:192.168.1.20", lan, own))
    }

    @Test
    fun `a missing or unreadable source is remote`() {
        listOf(null, "", "garbage", "192.168.1", "192.168.1.256", "fe80::1", "192.168.1.20.5").forEach {
            assertEquals(ConnectionOrigin.REMOTE, connectionOrigin(it, lan, own), it.toString())
        }
    }

    @Test
    fun `the gate lets a remote source through only while remote access is on`() {
        var remoteAccess = false
        val gate = OriginGate(lan, { remoteAccess }, { own })

        val remote = gate.originOf("203.0.113.5")
        assertEquals(false, gate.admits(remote))
        assertEquals(true, gate.admits(gate.originOf("192.168.1.20")))

        remoteAccess = true
        assertEquals(true, gate.admits(remote))
    }
}
