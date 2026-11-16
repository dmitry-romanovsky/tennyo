package app.tennyo.host.engine.transport

/**
 * One IPv4 address of one network interface, as an adapter enumerates it. [prefixLength] is the interface's own subnet
 * size; [displayName] is the platform's second name for the interface (Java reports both), and [isTunnel] is set for a
 * point-to-point or sub-interface the platform itself flags as virtual.
 */
data class InterfaceAddress(
    val interfaceName: String,
    val address: String,
    val prefixLength: Int,
    val displayName: String = interfaceName,
    val isTunnel: Boolean = false,
)

/** Which network a listening socket serves: the one the phone shares with this computer, or a private overlay. */
enum class ListenKind { LAN, OVERLAY }

enum class OverlayNetwork { TAILNET_RANGE, ZEROTIER, WIREGUARD }

data class ListenAddress(
    val address: String,
    val kind: ListenKind,
    val network: OverlayNetwork?,
    val prefixLength: Int,
)

/**
 * The addresses the listener binds, shared by both editors. LAN addresses are RFC 1918 ones on real interfaces,
 * falling back to virtual ones only when nothing else is up; an interface named for an overlay network is never LAN.
 * With [remoteAccess] on, overlay addresses follow: anything in 100.64.0.0/10, and RFC 1918 on a ZeroTier, WireGuard or
 * NetBird interface.
 *
 * Selection is by range and name, not identity: a commercial VPN in 100.64.0.0/10 (Cloudflare WARP) is selected too.
 * The origin gate, not the selection, is what keeps such an address harmless. Nothing is selected without a LAN
 * address, because the listener still needs one to start.
 */
fun selectListenAddresses(interfaces: List<InterfaceAddress>, remoteAccess: Boolean): List<ListenAddress> {
    val ipv4 = interfaces.filter { parseIpv4(it.address) != null }
    val lan = ipv4.filter { isPrivateIpv4(it.address) && it.overlayByName() == null }
    val lanAddresses = lan.filterNot { it.isVirtual() }.ifEmpty { lan }
        .map { ListenAddress(it.address, ListenKind.LAN, null, it.prefixLength) }
        .distinctBy { it.address }
    if (lanAddresses.isEmpty() || !remoteAccess) return lanAddresses
    val overlay = ipv4.mapNotNull { entry ->
        entry.overlayNetwork()?.let { ListenAddress(entry.address, ListenKind.OVERLAY, it, entry.prefixLength) }
    }
    return lanAddresses + overlay.filter { candidate -> lanAddresses.none { it.address == candidate.address } }
        .distinctBy { it.address }
}

/** RFC 1918: the only ranges the link is offered on as LAN, so it is never advertised to a public address. */
fun isPrivateIpv4(address: String): Boolean {
    val octets = ipv4Octets(address) ?: return false
    return octets[0] == CLASS_A_PRIVATE ||
            (octets[0] == CLASS_B_PRIVATE && octets[1] in CLASS_B_PRIVATE_SECOND_OCTETS) ||
            (octets[0] == CLASS_C_PRIVATE && octets[1] == CLASS_C_PRIVATE_SECOND_OCTET)
}

/** Whether [peer] and [local] are both IPv4 and share the first [prefixLength] bits. */
internal fun isOnLink(peer: String, local: String, prefixLength: Int): Boolean {
    val peerBits = parseIpv4(peer) ?: return false
    val localBits = parseIpv4(local) ?: return false
    if (prefixLength !in 1..IPV4_BITS) return false
    val mask = (ALL_ONES shl (IPV4_BITS - prefixLength)) and ALL_ONES
    return (peerBits and mask) == (localBits and mask)
}

private fun InterfaceAddress.overlayByName(): OverlayNetwork? =
    overlayNetworkOf(interfaceName) ?: overlayNetworkOf(displayName)

private fun InterfaceAddress.overlayNetwork(): OverlayNetwork? = when {
    isTailnetRange(address) -> OverlayNetwork.TAILNET_RANGE
    isPrivateIpv4(address) -> overlayByName()
    else -> null
}

private fun InterfaceAddress.isVirtual(): Boolean =
    isTunnel || isVirtualInterfaceName(interfaceName) || isVirtualInterfaceName(displayName)

/** 100.64.0.0/10, the carrier-grade NAT range Tailscale and NetBird assign from. */
private fun isTailnetRange(address: String): Boolean {
    val octets = ipv4Octets(address) ?: return false
    return octets[0] == TAILNET_FIRST_OCTET && octets[1] in TAILNET_SECOND_OCTETS
}

private fun ipv4Octets(address: String): List<Int>? {
    val parts = address.split('.')
    val octets = parts.map { part ->
        part.takeIf { it.length in 1..MAX_OCTET_DIGITS && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= MAX_OCTET }
    }
    return if (parts.size == IPV4_OCTETS) octets.filterNotNull().takeIf { it.size == IPV4_OCTETS } else null
}

private fun parseIpv4(address: String): Long? =
    ipv4Octets(address)?.fold(0L) { bits, octet -> (bits shl OCTET_BITS) or octet.toLong() }

private const val IPV4_OCTETS = 4
private const val IPV4_BITS = 32
private const val OCTET_BITS = 8
private const val MAX_OCTET = 255
private const val MAX_OCTET_DIGITS = 3
private const val ALL_ONES = 0xFFFF_FFFFL
private const val CLASS_A_PRIVATE = 10
private const val CLASS_B_PRIVATE = 172
private val CLASS_B_PRIVATE_SECOND_OCTETS = 16..31
private const val CLASS_C_PRIVATE = 192
private const val CLASS_C_PRIVATE_SECOND_OCTET = 168
private const val TAILNET_FIRST_OCTET = 100
private val TAILNET_SECOND_OCTETS = 64..127
