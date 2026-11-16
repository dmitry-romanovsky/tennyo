package app.tennyo.host.engine.transport

/**
 * Whether an interface is a virtual or VPN adapter rather than the network a phone shares with this computer. The link
 * and its DNS-SD advertisement use such an interface only when nothing else is up: a VPN usually carries the default
 * route, so a host advertising on it answers into the tunnel, never onto the Wi-Fi.
 *
 * [name] is whatever the platform reports — Node's friendly name (`CloudflareWARP`, `vEthernet (WSL)`) or Java's
 * display name (`Cloudflare WARP Interface Tunnel`); spaces and case are ignored.
 */
fun isVirtualInterfaceName(name: String): Boolean {
    val compact = name.replace(" ", "").lowercase()
    return VIRTUAL_PREFIXES.any(compact::startsWith) || VIRTUAL_PRODUCTS.any(compact::contains)
}

private val VIRTUAL_PREFIXES = listOf("vethernet", "veth", "docker", "br-", "utun", "tun", "tap", "virbr")
private val VIRTUAL_PRODUCTS = listOf(
    "warp", "wireguard", "openvpn", "nordlynx", "protonvpn", "tailscale", "zerotier",
    "hyper-v", "virtualbox", "vmware", "wsl",
)

/**
 * The overlay network an interface belongs to, recognised by name: ZeroTier, WireGuard and NetBird use arbitrary
 * ranges, often RFC 1918, so only the interface says what they are. Best effort: `utunN`, `feth*` and custom tunnel
 * names are missed, and such a tunnel's callers are then classified like LAN callers.
 */
fun overlayNetworkOf(interfaceName: String): OverlayNetwork? {
    val compact = interfaceName.replace(" ", "").lowercase()
    return when {
        ZEROTIER_PREFIXES.any(compact::startsWith) -> OverlayNetwork.ZEROTIER
        WIREGUARD_PREFIXES.any(compact::startsWith) -> OverlayNetwork.WIREGUARD
        else -> null
    }
}

private val ZEROTIER_PREFIXES = listOf("zerotier", "zt")
private val WIREGUARD_PREFIXES = listOf("wireguard", "wg", "netbird", "wt")
