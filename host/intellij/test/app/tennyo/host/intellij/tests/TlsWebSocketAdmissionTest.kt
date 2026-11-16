package app.tennyo.host.intellij.tests

import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.transport.AdmissionPolicy
import app.tennyo.host.engine.transport.DispatchLimiter
import app.tennyo.host.engine.transport.ListenAddress
import app.tennyo.host.engine.transport.ListenKind
import app.tennyo.host.engine.transport.OriginGate
import app.tennyo.host.engine.transport.OverlayNetwork
import app.tennyo.host.engine.transport.TransportConformanceCase
import app.tennyo.host.engine.transport.TransportConformanceCases
import app.tennyo.host.intellij.LinkHttpHandler
import app.tennyo.host.intellij.OpenConnections
import app.tennyo.host.intellij.OriginGateHandler
import app.tennyo.host.intellij.PROVISIONAL_TLS_PERMIT
import app.tennyo.host.intellij.TlsLifecycleObserver
import app.tennyo.host.intellij.bindListenSet
import app.tennyo.host.intellij.installLinkPipeline
import com.intellij.testFramework.junit5.TestApplication
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

@TestApplication
@Suppress("DEPRECATION")
internal class TlsWebSocketAdmissionTest {
    @Test
    fun `rate limited channel upgrade releases its socket permit`() {
        val policy = AdmissionPolicy()
        repeat(4) { sourceIndex ->
            repeat(AdmissionPolicy.MAX_PUBLIC_REQUESTS_PER_MINUTE) {
                assertTrue(
                    policy.admitRequest(
                        "192.168.3.${sourceIndex + 1}",
                        isLivenessProbe = false,
                        nowMillis = 1_000L
                    )
                )
            }
        }
        val provisional = requireNotNull(policy.beginTlsHandshake("192.168.3.10"))
        policy.finishTlsHandshake(provisional)
        val channel = EmbeddedChannel(LinkHttpHandler(policy, directLimiter()))
        channel.attr(PROVISIONAL_TLS_PERMIT).set(provisional)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            LinkEndpoints.CHANNEL,
        )
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)

        try {
            channel.writeInbound(request)

            assertEquals(0, policy.snapshot().linkSockets)
            assertEquals(0, policy.snapshot().provisionalTlsChannels)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `actual handler returns post tls 429 and releases a rejected channel provisional permit`() {
        val policy = AdmissionPolicy()
        repeat(AdmissionPolicy.MAX_LINK_SOCKETS) { sourceIndex ->
            requireNotNull(policy.openLinkSocket("192.168.1.${sourceIndex + 1}"))
        }
        val provisional = requireNotNull(policy.beginTlsHandshake("192.168.2.1"))
        policy.finishTlsHandshake(provisional)
        val channel = EmbeddedChannel(LinkHttpHandler(policy, directLimiter()))
        channel.attr(PROVISIONAL_TLS_PERMIT).set(provisional)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            LinkEndpoints.CHANNEL,
        )
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)

        channel.writeInbound(request)
        val response = channel.readOutbound<FullHttpResponse>()

        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS, response.status())
        assertEquals(0, policy.snapshot().provisionalTlsChannels)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `ordinary capacity leaves room for a fresh tls websocket upgrade`() {
        val policy = AdmissionPolicy()
        repeat(AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS) { channelIndex ->
            requireNotNull(
                policy.openOrdinaryHttp(
                    "127.0.0.${channelIndex / AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE + 2}",
                ),
            )
        }
        val certificate = SelfSignedCertificate("localhost")
        val boss = NioEventLoopGroup(1)
        val workers = NioEventLoopGroup(1)
        val clientGroup = NioEventLoopGroup(1)
        val clientResponse = CountDownLatch(1)
        var responseStatus: HttpResponseStatus? = null
        try {
            val serverContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build()
            val server = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        channel.pipeline().addLast(serverContext.newHandler(channel.alloc()))
                        channel.pipeline().addLast(TlsLifecycleObserver(policy))
                        channel.pipeline().addLast(HttpServerCodec())
                        channel.pipeline().addLast(HttpObjectAggregator(8 * 1024))
                        channel.pipeline().addLast(
                            LinkHttpHandler(policy, directLimiter()),
                        )
                    }
                }).bind(0).syncUninterruptibly().channel()
            val port = (server.localAddress() as java.net.InetSocketAddress).port
            val clientContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            val client = Bootstrap().group(clientGroup).channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        val tls = clientContext.newHandler(channel.alloc(), "localhost", port)
                        channel.pipeline().addLast(tls)
                        channel.pipeline().addLast(HttpClientCodec())
                        channel.pipeline().addLast(HttpObjectAggregator(8 * 1024))
                        channel.pipeline().addLast(object : SimpleChannelInboundHandler<FullHttpResponse>() {
                            override fun channelRead0(context: ChannelHandlerContext, response: FullHttpResponse) {
                                responseStatus = response.status()
                                clientResponse.countDown()
                            }
                        })
                        tls.handshakeFuture().addListener { handshake ->
                            if (!handshake.isSuccess) {
                                channel.close()
                                return@addListener
                            }
                            val request = DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.GET,
                                LinkEndpoints.CHANNEL,
                            )
                            request.headers().set(HttpHeaderNames.HOST, "localhost:$port")
                            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
                            request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
                            request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
                            request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
                            channel.writeAndFlush(request)
                        }
                    }
                }).connect("127.0.0.1", port).syncUninterruptibly().channel()

            assertTrue(clientResponse.await(5, TimeUnit.SECONDS))
            assertEquals(HttpResponseStatus.SWITCHING_PROTOCOLS, responseStatus)
            assertEquals(1, policy.snapshot().linkSockets)
            assertEquals(0, policy.snapshot().provisionalTlsChannels)
            client.close().syncUninterruptibly()
            server.close().syncUninterruptibly()
        } finally {
            clientGroup.shutdownGracefully().syncUninterruptibly()
            workers.shutdownGracefully().syncUninterruptibly()
            boss.shutdownGracefully().syncUninterruptibly()
            certificate.delete()
        }
    }

    @Test
    fun `a remote source is closed before TLS while remote access is off`() {
        val policy = AdmissionPolicy()
        val reached = AtomicBoolean()

        val channel = acceptedFrom("203.0.113.5", remoteAccess = false, policy, reached)

        assertFalse(channel.isOpen, "the remote source was kept open")
        assertFalse(reached.get(), "the connection got past the gate")
        assertEquals(0, policy.snapshot().pendingTlsHandshakes, "a handshake permit was taken")
    }

    @Test
    fun `a local source is let through to the TLS handshake`() {
        val policy = AdmissionPolicy()
        val reached = AtomicBoolean()

        val channel = acceptedFrom("192.168.1.20", remoteAccess = false, policy, reached)

        assertTrue(channel.isOpen)
        assertTrue(reached.get())
        assertEquals(1, policy.snapshot().pendingTlsHandshakes)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `a remote source is let through once remote access is on`() {
        val policy = AdmissionPolicy()
        val reached = AtomicBoolean()

        val channel = acceptedFrom("203.0.113.5", remoteAccess = true, policy, reached)

        assertTrue(reached.get())
        assertEquals(1, policy.snapshot().pendingTlsHandshakes)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `an open remote connection is closed when remote access turns off, and a local one stays`() {
        val connections = OpenConnections()
        val remote = acceptedFrom("203.0.113.5", remoteAccess = true, AdmissionPolicy(), AtomicBoolean(), connections)
        val local = acceptedFrom("192.168.1.20", remoteAccess = true, AdmissionPolicy(), AtomicBoolean(), connections)

        connections.closeRemote()

        assertFalse(remote.isOpen, "the remote connection kept running")
        assertTrue(local.isOpen, "the local connection was closed")
        local.finishAndReleaseAll()
    }

    @Test
    fun `connections of a socket that is dropped are all closed`() {
        val connections = OpenConnections()
        val local = acceptedFrom("192.168.1.20", remoteAccess = false, AdmissionPolicy(), AtomicBoolean(), connections)

        connections.closeAll()

        assertFalse(local.isOpen)
    }

    @Test
    fun `an overlay address that cannot bind does not take the LAN listener down`() {
        val lan = ListenAddress("192.168.1.10", ListenKind.LAN, null, 24)
        val overlay = ListenAddress("100.101.2.3", ListenKind.OVERLAY, OverlayNetwork.TAILNET_RANGE, 32)

        val bound = bindListenSet(
            listOf(lan, overlay),
            port = 0,
            bind = { address, _ -> if (address == overlay) error("cannot assign requested address") else EmbeddedChannel() },
            portOf = { 63_343 },
        )

        assertEquals(listOf(lan), bound.keys.toList())
        bound.values.forEach { assertTrue(it.isOpen) }
    }

    @Test
    fun `a LAN address that cannot bind fails the set and closes what bound`() {
        val first = ListenAddress("192.168.1.10", ListenKind.LAN, null, 24)
        val second = ListenAddress("192.168.1.11", ListenKind.LAN, null, 24)
        val opened = mutableListOf<EmbeddedChannel>()

        val failure = runCatching {
            bindListenSet(
                listOf(first, second),
                port = 0,
                bind = { address, _ -> if (address == second) error("in use") else EmbeddedChannel().also(opened::add) },
                portOf = { 63_343 },
            )
        }

        assertTrue(failure.isFailure)
        assertTrue(opened.none { it.isOpen })
    }

    /** A connection from [source] to a LAN socket on 192.168.1.10/24, as far as the gate and the TLS observer see it. */
    private fun acceptedFrom(
        source: String,
        remoteAccess: Boolean,
        policy: AdmissionPolicy,
        reached: AtomicBoolean,
        connections: OpenConnections = OpenConnections(),
    ): EmbeddedChannel {
        val gate = OriginGate(
            ListenAddress("192.168.1.10", ListenKind.LAN, null, prefixLength = 24),
            remoteAccess = { remoteAccess },
            ownAddresses = { setOf("192.168.1.10") },
        )
        val handlers = arrayOf<ChannelHandler>(
            OriginGateHandler(gate, connections),
            TlsLifecycleObserver(policy),
            object : ChannelInboundHandlerAdapter() {
                override fun channelActive(context: ChannelHandlerContext) {
                    reached.set(true)
                }
            },
        )
        return object : EmbeddedChannel(*handlers) {
            override fun remoteAddress0(): SocketAddress? = InetSocketAddress(InetAddress.getByName(source), PEER_PORT)
        }
    }

    @TestFactory
    fun `listener answers every transport conformance case`(): List<DynamicTest> =
        TransportConformanceCases.all.map { case ->
            DynamicTest.dynamicTest(case.name) { assertConformance(case) }
        }

    /** Runs [case] against the listener's real accepted-channel pipeline with fresh admission state. */
    private fun assertConformance(case: TransportConformanceCase) {
        val certificate = SelfSignedCertificate("localhost")
        val boss = NioEventLoopGroup(1)
        val workers = NioEventLoopGroup(1)
        val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val serverContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build()
            val limiter = DispatchLimiter(dispatchScope)
            val server = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        channel.installLinkPipeline(serverContext, AdmissionPolicy(), limiter, admitEveryone, OpenConnections())
                    }
                }).bind("127.0.0.1", 0).syncUninterruptibly().channel()
            val port = (server.localAddress() as java.net.InetSocketAddress).port
            try {
                val exchange = rawExchange(port, case)
                assertEquals(case.expectedStatus, exchange.status, "status for ${case.name}")
                assertEquals(case.expectedErrorCode, exchange.errorCode, "error code for ${case.name}")
                assertEquals(case.closesConnection, exchange.closed, "connection close for ${case.name}")
            } finally {
                server.close().syncUninterruptibly()
            }
        } finally {
            dispatchScope.cancel()
            workers.shutdownGracefully().syncUninterruptibly()
            boss.shutdownGracefully().syncUninterruptibly()
            certificate.delete()
        }
    }

    private class RawExchange(val status: Int?, val errorCode: String?, val closed: Boolean)

    /** Writes the raw request, reads the first response, then waits briefly to see whether the listener closes. */
    private fun rawExchange(port: Int, case: TransportConformanceCase): RawExchange {
        val socket = if (case.overTls) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, InsecureTrustManagerFactory.INSTANCE.trustManagers, null)
            (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).also { it.startHandshake() }
        } else {
            Socket("127.0.0.1", port)
        }
        socket.use {
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(case.rawRequest.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            val input = socket.getInputStream()
            val head = readHead(input) ?: return RawExchange(status = null, errorCode = null, closed = true)
            val status = head.lineSequence().first().split(' ')[1].toInt()
            val length = head.lineSequence()
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = String(input.readNBytes(length), StandardCharsets.UTF_8)
            val errorCode = body.substringAfter("\"error\":\"", "").substringBefore('"').ifEmpty { null }
            socket.soTimeout = if (case.closesConnection) 5_000 else 500
            val closed = try {
                input.read() == -1
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true
            }
            return RawExchange(status, errorCode, closed)
        }
    }

    /** The response head up to the blank line, or `null` when the connection ended before any response. */
    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = try {
                input.read()
            } catch (_: IOException) {
                -1
            }
            if (next == -1) return null
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun directLimiter() = DispatchLimiter(CoroutineScope(Dispatchers.Unconfined))

    private companion object {
        const val PEER_PORT = 50_000

        /** Loopback is a remote origin, so the conformance cases, which connect over it, need the gate open. */
        val admitEveryone = OriginGate(
            ListenAddress("127.0.0.1", ListenKind.LAN, null, prefixLength = 8),
            remoteAccess = { true },
            ownAddresses = { emptySet() },
        )
    }
}
