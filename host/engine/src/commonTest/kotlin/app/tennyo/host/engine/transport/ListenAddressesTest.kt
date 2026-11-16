package app.tennyo.host.engine.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class ListenAddressesTest {
    private val wifi = InterfaceAddress("Wi-Fi", "192.168.1.20", 24)
    private val tailscale = InterfaceAddress("Tailscale", "100.101.2.3", 32)
    private val zeroTier = InterfaceAddress("ZeroTier One [abcd]", "10.147.17.5", 24)
    private val wsl = InterfaceAddress("vEthernet (WSL)", "172.20.0.1", 20)

    @Test
    fun `remote access off selects only the LAN address`() {
        val selected = selectListenAddresses(listOf(wifi, tailscale, zeroTier, wsl), remoteAccess = false)

        assertEquals(listOf(ListenAddress("192.168.1.20", ListenKind.LAN, null, 24)), selected)
    }

    @Test
    fun `remote access on adds the overlay addresses after the LAN one and never WSL`() {
        val selected = selectListenAddresses(listOf(wifi, tailscale, zeroTier, wsl), remoteAccess = true)

        assertEquals(
            listOf(
                ListenAddress("192.168.1.20", ListenKind.LAN, null, 24),
                ListenAddress("100.101.2.3", ListenKind.OVERLAY, OverlayNetwork.TAILNET_RANGE, 32),
                ListenAddress("10.147.17.5", ListenKind.OVERLAY, OverlayNetwork.ZEROTIER, 24),
            ),
            selected,
        )
    }

    @Test
    fun `a WireGuard private address is overlay and never LAN, even as the only interface`() {
        val wireGuard = InterfaceAddress("wg0", "10.8.0.2", 24)

        assertEquals(emptyList(), selectListenAddresses(listOf(wireGuard), remoteAccess = false))
        assertEquals(emptyList(), selectListenAddresses(listOf(wireGuard), remoteAccess = true))
        assertEquals(
            OverlayNetwork.WIREGUARD,
            selectListenAddresses(listOf(wifi, wireGuard), remoteAccess = true).last().network,
        )
    }

    @Test
    fun `a commercial VPN in the tailnet range is selected only when remote access is on`() {
        val warp = InterfaceAddress("CloudflareWARP", "100.96.0.1", 32)

        assertEquals(listOf("192.168.1.20"), selectListenAddresses(listOf(wifi, warp), false).map { it.address })
        assertEquals(
            listOf("192.168.1.20", "100.96.0.1"),
            selectListenAddresses(listOf(wifi, warp), true).map { it.address },
        )
    }

    @Test
    fun `a virtual interface is LAN only when nothing else is up`() {
        assertEquals(listOf("172.20.0.1"), selectListenAddresses(listOf(wsl), false).map { it.address })
        assertEquals(listOf("192.168.1.20"), selectListenAddresses(listOf(wsl, wifi), false).map { it.address })
        assertEquals(
            listOf("192.168.1.20"),
            selectListenAddresses(
                listOf(InterfaceAddress("ppp0", "10.0.0.2", 32, isTunnel = true), wifi),
                false,
            ).map { it.address },
        )
    }

    @Test
    fun `public and malformed addresses are never selected`() {
        val selected = selectListenAddresses(
            listOf(wifi, InterfaceAddress("eth0", "203.0.113.5", 24), InterfaceAddress("eth1", "10.0.0", 24)),
            remoteAccess = true,
        )

        assertEquals(listOf("192.168.1.20"), selected.map { it.address })
    }

    @Test
    fun `the interface name decides ZeroTier and WireGuard, not the address`() {
        assertEquals(OverlayNetwork.ZEROTIER, overlayNetworkOf("ZeroTier One [abcd]"))
        assertEquals(OverlayNetwork.ZEROTIER, overlayNetworkOf("ztabcdef"))
        assertEquals(OverlayNetwork.WIREGUARD, overlayNetworkOf("WireGuard Tunnel"))
        assertEquals(OverlayNetwork.WIREGUARD, overlayNetworkOf("wt0"))
        assertEquals(null, overlayNetworkOf("Wi-Fi"))
        assertEquals(null, overlayNetworkOf("wlan0"))
    }
}
