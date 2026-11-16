package app.tennyo.host.intellij

import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.OverlayEndpoint
import app.tennyo.host.engine.transport.AdmissionPolicy
import app.tennyo.host.engine.transport.ConnectionOrigin
import app.tennyo.host.engine.transport.DispatchLimiter
import app.tennyo.host.engine.transport.InterfaceAddress
import app.tennyo.host.engine.transport.ListenAddress
import app.tennyo.host.engine.transport.ListenKind
import app.tennyo.host.engine.transport.ListenerLeasePolicy
import app.tennyo.host.engine.transport.OriginGate
import app.tennyo.host.engine.transport.selectListenAddresses
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.GlobalEventExecutor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import co.touchlab.kermit.Logger as EngineLog

/** Owns the only externally reachable Tennyo listener. */
@Service(Service.Level.APP)
internal class LinkServerService(coroutineScope: CoroutineScope) : Disposable {
    private val boss: EventLoopGroup = NioEventLoopGroup(1)
    private val workers: EventLoopGroup = NioEventLoopGroup()
    private val bound = linkedMapOf<ListenAddress, Channel>()
    private val connections = mutableMapOf<ListenAddress, OpenConnections>()
    private val leasePolicy = ListenerLeasePolicy()
    private val lock = Any()
    internal val admissionPolicy = AdmissionPolicy()
    internal val discoveryRoutes = DiscoveryRoutes(IdeProjectDirectory)
    internal val dispatchLimiter = DispatchLimiter(
        CoroutineScope(
            coroutineScope.coroutineContext + Dispatchers.IO + CoroutineName("Tennyo Companion HTTP dispatch"),
        ),
    )

    init {
        EngineLog.setLogWriters(IdeLogWriter())
    }

    @Volatile
    private var identity: TransportIdentity? = null

    @Volatile
    private var boundPort: Int? = null
    private var reconciliation: ScheduledFuture<*>? = null

    /** The bound addresses as of the last change, readable without the lock so the popup never waits on a bind. */
    @Volatile
    private var boundAddresses: List<ListenAddress> = emptyList()

    /** Every IPv4 address of this machine as of the last reconcile; a connection from one is a local proxy. */
    @Volatile
    private var ownAddresses: Set<String> = emptySet()

    fun acquire(preferredPort: Int?, ownerKind: String): ServerLease = synchronized(lock) {
        val leaseId = leasePolicy.acquire(ownerKind)
        fun lease() = ServerLease(::requireEndpoints) { release(leaseId, ownerKind) }
        if (bound.isNotEmpty()) {
            logLease("acquired", ownerKind)
            return lease()
        }
        try {
            val interfaces = interfaceAddresses()
            val addresses = selectListenAddresses(interfaces, remoteAccess())
            check(addresses.isNotEmpty()) { "No eligible private IPv4 network interface is available" }
            ownAddresses = interfaces.mapTo(mutableSetOf()) { it.address }
            val transportIdentity = loadOrCreatePersistentTransportIdentity()
            val ports = sequenceOf(preferredPort).filterNotNull() + (63_343..63_383).asSequence() + sequenceOf(0)
            var lastFailure: Throwable? = null
            for (port in ports.distinct()) {
                val attempted = runCatching {
                    val bindOne = { address: ListenAddress, bindPort: Int ->
                        bind(address, bindPort, transportIdentity)
                    }
                    bindListenSet(addresses, port, bindOne) { (it.localAddress() as InetSocketAddress).port }
                }.onFailure { lastFailure = it }.getOrNull()
                if (attempted != null) {
                    identity = transportIdentity
                    boundPort = (attempted.values.first().localAddress() as InetSocketAddress).port
                    bound += attempted
                    boundAddresses = bound.keys.toList()
                    reconciliation?.cancel(false)
                    reconciliation = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                        { reconcile() }, 30, 30, TimeUnit.SECONDS
                    )
                    LOG.info("Tennyo remote TLS listener bound on ${bound.size} address(es), port=$boundPort")
                    logLease("acquired", ownerKind)
                    return lease()
                }
            }
            throw IllegalStateException("Unable to bind a complete private IPv4 listener set", lastFailure)
        } catch (failure: Throwable) {
            // Nothing is listening, so the connection sets the failed attempts registered belong to no socket.
            connections.clear()
            leasePolicy.release(leaseId)
            throw failure
        }
    }

    private fun release(leaseId: String, ownerKind: String) = synchronized(lock) {
        if (!leasePolicy.release(leaseId)) return@synchronized
        logLease("released", ownerKind)
        if (leasePolicy.isListenerRequired) return@synchronized
        reconciliation?.cancel(false)
        reconciliation = null
        bound.values.forEach { it.close().syncUninterruptibly() }
        bound.clear()
        connections.values.forEach(OpenConnections::closeAll)
        connections.clear()
        boundAddresses = emptyList()
        boundPort = null
        identity = null
    }

    /**
     * Brings the bound sockets in line with [selectListenAddresses]: it closes the ones whose address left the
     * selection and binds new ones on the port the listener already holds. It runs every 30 seconds and whenever
     * Remote access is switched.
     */
    fun reconcile() = synchronized(lock) {
        val activeIdentity = identity ?: return@synchronized
        val port = boundPort ?: return@synchronized
        val interfaces = interfaceAddresses()
        ownAddresses = interfaces.mapTo(mutableSetOf()) { it.address }
        val selected = selectListenAddresses(interfaces, remoteAccess())
        if (!remoteAccess()) connections.values.forEach(OpenConnections::closeRemote)
        bound.keys.filter { it !in selected }.forEach { address ->
            bound.remove(address)?.close()
            connections.remove(address)?.closeAll()
        }
        selected.filter { it !in bound }.forEach { address ->
            runCatching { bind(address, port, activeIdentity) }
                .onSuccess { bound[address] = it }
                .onFailure { LOG.warn("Unable to reconcile Tennyo listener address ${address.address}", it) }
        }
        boundAddresses = bound.keys.toList()
    }

    /** Where the listener answers right now, or `null` while it is down or has no LAN address. */
    fun endpoints(): HostEndpoints? {
        val port = boundPort ?: return null
        val fingerprint = identity?.fingerprint ?: return null
        val addresses = boundAddresses.sortedBy { it.kind }
        val lan = addresses.filter { it.kind == ListenKind.LAN }.map { linkUrl(it.address, port) }
        val primary = lan.firstOrNull() ?: return null
        return HostEndpoints(
            primary = primary,
            alternates = lan.drop(1),
            transportFingerprint = fingerprint,
            port = port,
            overlay = addresses.filter { it.kind == ListenKind.OVERLAY }.map {
                OverlayEndpoint(checkNotNull(it.network), it.address, linkUrl(it.address, port))
            },
        )
    }

    private fun requireEndpoints(): HostEndpoints =
        endpoints() ?: error("No private IPv4 address is available for the link")

    private fun linkUrl(address: String, port: Int) = "https://$address:$port${LinkEndpoints.PREFIX}"

    private fun remoteAccess(): Boolean = service<HostGlobalSettings>().isRemoteAccessEnabled()

    private fun bind(address: ListenAddress, port: Int, identity: TransportIdentity): Channel {
        val gate = OriginGate(address, ::remoteAccess) { ownAddresses }
        val open = OpenConnections().also { connections[address] = it }
        return ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                io.netty.channel.WriteBufferWaterMark(32 * 1024, 128 * 1024)
            )
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(channel: SocketChannel) {
                    runCatching {
                        channel.installLinkPipeline(
                            identity.sslContext,
                            admissionPolicy,
                            dispatchLimiter,
                            gate,
                            open,
                            discoveryRoutes = discoveryRoutes,
                        )
                    }.onFailure { failure ->
                        LOG.debug(
                            "Tennyo accepted-channel initialization failed ${channel.transportDescription()}",
                            failure
                        )
                        throw failure
                    }
                }
            }).bind(InetAddress.getByName(address.address), port).syncUninterruptibly().channel()
    }

    override fun dispose() {
        synchronized(lock) {
            leasePolicy.clear()
            reconciliation?.cancel(false)
            bound.values.forEach { it.close().syncUninterruptibly() }
            bound.clear()
            connections.clear()
            boundAddresses = emptyList()
        }
        boss.shutdownGracefully(); workers.shutdownGracefully()
        EngineLog.setLogWriters()
    }

    private fun logLease(action: String, ownerKind: String) {
        LOG.info("Tennyo remote TLS listener lease $action ownerKind=$ownerKind count=${leasePolicy.count}")
    }

    private companion object {
        val LOG = Logger.getInstance(LinkServerService::class.java)
    }
}

/** The machine's up, non-loopback IPv4 addresses with their subnet sizes; the one input the address rules read. */
internal fun interfaceAddresses(): List<InterfaceAddress> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { network ->
            network.interfaceAddresses.asSequence()
                .filter { it.address is Inet4Address }
                .map { entry ->
                    InterfaceAddress(
                        interfaceName = network.name,
                        address = entry.address.hostAddress,
                        prefixLength = entry.networkPrefixLength.toInt(),
                        displayName = network.displayName.orEmpty(),
                        // Java does not flag a VPN tunnel such as Cloudflare WARP as virtual, so its name decides too.
                        isTunnel = network.isVirtual || network.isPointToPoint,
                    )
                }
        }
        .toList()
}.getOrDefault(emptyList())

/**
 * The accepted-channel pipeline of the listener, in order: the origin gate, TLS, admission bookkeeping, idle detection,
 * HTTP framing, the pre-aggregation size guard, and the routes.
 */
internal fun SocketChannel.installLinkPipeline(
    sslContext: SslContext,
    admissionPolicy: AdmissionPolicy,
    dispatchLimiter: DispatchLimiter,
    gate: OriginGate,
    connections: OpenConnections,
    discoveryRoutes: DiscoveryRoutes? = null,
) {
    val tlsHandler = sslContext.newHandler(alloc())
    tlsHandler.handshakeTimeoutMillis = TLS_HANDSHAKE_TIMEOUT_MILLIS
    pipeline().addLast("originGate", OriginGateHandler(gate, connections))
    pipeline().addLast("tls", tlsHandler)
    pipeline().addLast("tlsLifecycle", TlsLifecycleObserver(admissionPolicy))
    pipeline().addLast("idle", IdleStateHandler(IDLE_TIMEOUT_SECONDS, 0, 0, TimeUnit.SECONDS))
    pipeline().addLast("idleCloser", IdleChannelCloser())
    pipeline().addLast("http", HttpServerCodec(MAX_REQUEST_LINE_BYTES, MAX_HEADER_BYTES, MAX_CHUNK_BYTES, false))
    pipeline().addLast("sizeGuard", InboundSizeGuard())
    // Above every endpoint limit, so InboundSizeGuard answers an oversized body with its typed error first.
    pipeline().addLast("aggregate", HttpObjectAggregator(MAX_AGGREGATED_BODY_BYTES))
    pipeline().addLast(
        "routes",
        LinkHttpHandler(admissionPolicy, dispatchLimiter, discoveryRoutes),
    )
}

private const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
private const val IDLE_TIMEOUT_SECONDS = 30L
private const val MAX_REQUEST_LINE_BYTES = 4096
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_CHUNK_BYTES = 64 * 1024
private val MAX_AGGREGATED_BODY_BYTES =
    2 * maxOf(LinkEndpoints.PAIR_BODY_LIMIT_BYTES, LinkEndpoints.DISCOVER_BODY_LIMIT_BYTES)

/**
 * One holder's claim on the shared listener. [endpoints] is read when needed, because the listener's addresses change
 * while a lease is held.
 */
internal class ServerLease internal constructor(
    val endpoints: () -> HostEndpoints,
    private val releaseAction: () -> Unit,
) {
    private val released = java.util.concurrent.atomic.AtomicBoolean()

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * The first handler of every accepted connection. It decides the connection's origin and closes a remote one while
 * Remote access is off, before the TLS handler has read a byte, so a refused source costs no handshake permit.
 */
internal class OriginGateHandler(
    private val gate: OriginGate,
    private val connections: OpenConnections,
) : ChannelInboundHandlerAdapter() {
    override fun channelActive(context: ChannelHandlerContext) {
        val origin = gate.originOf(context.channel().admissionSource())
        if (!gate.admits(origin)) {
            LOG.info("Tennyo refused a remote source before TLS source=${context.channel().admissionSource()}")
            context.close()
            return
        }
        context.channel().attr(CONNECTION_ORIGIN).set(origin)
        connections.track(context.channel(), origin)
        context.fireChannelActive()
    }

    private companion object {
        val LOG = Logger.getInstance(OriginGateHandler::class.java)
    }
}

/**
 * The admitted connections of one listening socket. A channel group forgets a channel when it closes, so this only
 * ever holds the open ones; it lets Remote access ending, or the socket leaving the selection, end them too.
 */
internal class OpenConnections {
    private val all = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private val remote = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    fun track(channel: Channel, origin: ConnectionOrigin) {
        all.add(channel)
        if (origin == ConnectionOrigin.REMOTE) remote.add(channel)
    }

    fun closeRemote() {
        remote.close()
    }

    fun closeAll() {
        all.close()
    }
}

/**
 * Binds every LAN address on [port] (the first picks the port when it is 0) and then each overlay address on that
 * same port. The LAN set is mandatory and a failure closes whatever bound; an overlay address is best effort, so an
 * unbindable one never takes the LAN listener down, and the next reconcile tries it again.
 */
internal fun bindListenSet(
    addresses: List<ListenAddress>,
    port: Int,
    bind: (ListenAddress, Int) -> Channel,
    portOf: (Channel) -> Int,
): Map<ListenAddress, Channel> {
    val bound = linkedMapOf<ListenAddress, Channel>()
    val (lan, overlay) = addresses.partition { it.kind == ListenKind.LAN }
    var complete = false
    try {
        val first = bind(lan.first(), port)
        bound[lan.first()] = first
        val selectedPort = portOf(first)
        lan.drop(1).forEach { bound[it] = bind(it, selectedPort) }
        overlay.forEach { address ->
            runCatching { bind(address, selectedPort) }
                .onSuccess { bound[address] = it }
                .onFailure { BIND_LOG.warn("Unable to bind Tennyo overlay address ${address.address}", it) }
        }
        complete = true
    } finally {
        if (!complete) bound.values.forEach { it.close().syncUninterruptibly() }
    }
    return bound
}

private val BIND_LOG = Logger.getInstance(LinkServerService::class.java)

/** The origin [OriginGateHandler] decided; a channel without one is treated as remote. */
internal val CONNECTION_ORIGIN: AttributeKey<ConnectionOrigin> = AttributeKey.valueOf("tennyoRemote.origin")

internal class TlsLifecycleObserver(
    private val admissionPolicy: AdmissionPolicy,
) : ChannelInboundHandlerAdapter() {
    private val correlationId = UUID.randomUUID().toString().take(8)
    private var pendingPermit: AdmissionPolicy.TlsHandshakePermit? = null

    override fun channelActive(context: ChannelHandlerContext) {
        pendingPermit = admissionPolicy.beginTlsHandshake(context.channel().admissionSource())
        if (pendingPermit == null) {
            LOG.info(
                "Tennyo admission rejected correlation=$correlationId stage=pending_tls " +
                        "source=${context.channel().admissionSource()} counters=${admissionPolicy.snapshot()}"
            )
            context.close()
            return
        }
        context.fireChannelActive()
    }

    override fun userEventTriggered(context: ChannelHandlerContext, event: Any) {
        if (event is SslHandshakeCompletionEvent) {
            val permit = pendingPermit
            if (event.isSuccess) {
                permit?.let(admissionPolicy::finishTlsHandshake)
                context.channel().attr(PROVISIONAL_TLS_PERMIT).set(permit)
                pendingPermit = null
                val session = context.pipeline().get("tls")
                    ?.let { it as? io.netty.handler.ssl.SslHandler }
                    ?.engine()
                    ?.session
                LOG.debug(
                    "Tennyo TLS handshake succeeded correlation=$correlationId ${
                        context.channel().transportDescription()
                    } " +
                            "protocol=${session?.protocol.orEmpty()} cipher=${session?.cipherSuite.orEmpty()}"
                )
            } else {
                permit?.let(admissionPolicy::abortTlsHandshake)
                pendingPermit = null
                val failure = event.cause()
                // At info: a phone that refuses this certificate (a stale pin) shows up here and nowhere else.
                LOG.info(
                    "Tennyo TLS handshake failed correlation=$correlationId ${
                        context.channel().transportDescription()
                    } " +
                            "failure=${failure.javaClass.simpleName}:${failure.safeTransportMessage()}"
                )
            }
        }
        context.fireUserEventTriggered(event)
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        pendingPermit?.let(admissionPolicy::abortTlsHandshake)
        pendingPermit = null
        context.channel().attr(PROVISIONAL_TLS_PERMIT).getAndSet(null)?.let(admissionPolicy::abortTlsHandshake)
        context.fireChannelInactive()
    }

    private companion object {
        val LOG = Logger.getInstance(TlsLifecycleObserver::class.java)
    }
}

internal val PROVISIONAL_TLS_PERMIT: AttributeKey<AdmissionPolicy.TlsHandshakePermit> =
    AttributeKey.valueOf("tennyoRemote.provisionalTls")

private fun Throwable.safeTransportMessage(): String =
    message.orEmpty().replace(Regex("[\\r\\n]+"), " ").take(160)

private fun Channel.transportDescription(): String {
    val local = localAddress() as? InetSocketAddress
    val remote = remoteAddress() as? InetSocketAddress
    return "listener=${local?.address?.hostAddress.orEmpty()}:${local?.port ?: -1} " +
            "remote=${remote?.address?.hostAddress.orEmpty()}:${remote?.port ?: -1}"
}

private fun Channel.admissionSource(): String =
    (remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"

private class IdleChannelCloser : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(context: ChannelHandlerContext, event: Any) {
        if (event is IdleStateEvent) {
            context.close()
            return
        }
        context.fireUserEventTriggered(event)
    }
}
