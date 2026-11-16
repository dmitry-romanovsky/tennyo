package app.tennyo.host.engine.transport

/** Whether a connection comes from the network the phone shares with this computer, or from anywhere else. */
enum class ConnectionOrigin { LOCAL, REMOTE }

/**
 * Classifies one accepted connection. It is [ConnectionOrigin.LOCAL] only when it arrived on a LAN socket from an
 * RFC 1918 source that sits inside that interface's own subnet and is not one of this machine's addresses. Every
 * other connection is remote: overlay sockets, port-forwarded public sources, loopback, and local proxies such as
 * Tailscale Funnel or `cloudflared`, which connect from an address of this machine. An unreadable source is remote.
 *
 * This assumes no source NAT. A router that source-NATs a forward, a Tailscale subnet router or a LAN reverse proxy
 * can still look local, so the rule narrows pairing to the local network rather than proving it.
 */
fun connectionOrigin(source: String?, listen: ListenAddress, ownAddresses: Set<String>): ConnectionOrigin {
    if (listen.kind != ListenKind.LAN) return ConnectionOrigin.REMOTE
    val peer = source?.removePrefix(IPV4_MAPPED_PREFIX) ?: return ConnectionOrigin.REMOTE
    val local = isPrivateIpv4(peer) && peer !in ownAddresses && isOnLink(peer, listen.address, listen.prefixLength)
    return if (local) ConnectionOrigin.LOCAL else ConnectionOrigin.REMOTE
}

/**
 * What one listening socket knows to decide on a connection before any TLS: its own address, whether Remote access is
 * on, and the machine's own addresses. Both listeners ask it at accept.
 */
class OriginGate(
    private val listen: ListenAddress,
    private val remoteAccess: () -> Boolean,
    private val ownAddresses: () -> Set<String>,
) {
    fun originOf(source: String?): ConnectionOrigin = connectionOrigin(source, listen, ownAddresses())

    /** A remote connection is let through only while Remote access is on. */
    fun admits(origin: ConnectionOrigin): Boolean = origin == ConnectionOrigin.LOCAL || remoteAccess()
}

private const val IPV4_MAPPED_PREFIX = "::ffff:"
