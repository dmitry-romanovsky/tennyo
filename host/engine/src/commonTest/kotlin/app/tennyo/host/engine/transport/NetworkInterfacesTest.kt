package app.tennyo.host.engine.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfacesTest {
    @Test
    fun `vpn and virtual adapters are recognised by node and java names alike`() {
        listOf(
            "CloudflareWARP", "Cloudflare WARP Interface Tunnel", "vEthernet (WSL)", "WireGuard Tunnel",
            "Tailscale", "docker0", "utun3", "VMware Virtual Ethernet Adapter for VMnet8",
        ).forEach { assertTrue(isVirtualInterfaceName(it), it) }
        listOf("Wi-Fi", "Ethernet", "en0", "wlan0", "Intel(R) Wi-Fi 6E AX211 160MHz").forEach {
            assertFalse(isVirtualInterfaceName(it), it)
        }
    }
}
