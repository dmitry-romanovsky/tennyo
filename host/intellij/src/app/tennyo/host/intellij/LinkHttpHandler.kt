package app.tennyo.host.intellij

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.LinkLimits
import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.rpc.PublicEndpoints
import app.tennyo.host.engine.session.LinkChannel
import app.tennyo.host.engine.transport.AdmissionPolicy
import app.tennyo.host.engine.transport.ConnectionOrigin
import app.tennyo.host.engine.transport.DispatchLimiter
import app.tennyo.host.engine.transport.HostHttpHeaders
import app.tennyo.host.engine.transport.HostHttpRequest
import app.tennyo.host.engine.transport.HostHttpResponse
import app.tennyo.host.engine.transport.HttpStatus
import app.tennyo.host.engine.transport.LinkSocket
import app.tennyo.host.engine.transport.RequestShapeRules
import app.tennyo.host.engine.transport.toResponse
import app.tennyo.host.engine.transport.transportRejection
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.ssl.SslHandler
import io.netty.util.AttributeKey
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Netty side of the listener: it converts each request once, applies [RequestShapeRules] and admission, runs the
 * public endpoints through [dispatchLimiter], and upgrades the channel path into a [LinkChannel]. Everything it writes
 * over HTTP goes through [respond].
 */
internal class LinkHttpHandler(
    private val admissionPolicy: AdmissionPolicy = service<LinkServerService>().admissionPolicy,
    private val dispatchLimiter: DispatchLimiter = service<LinkServerService>().dispatchLimiter,
    discoveryRoutes: DiscoveryRoutes? = null,
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    private val endpoints = PublicEndpoints(discoveryRoutes)
    private val requestInFlight = AtomicBoolean()

    override fun handlerAdded(context: ChannelHandlerContext) {
        context.pipeline().addBefore(context.name(), "responseGate", HttpResponseGate(requestInFlight))
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        releaseAdmission(context)
        context.fireChannelInactive()
    }

    override fun channelRead0(context: ChannelHandlerContext, message: FullHttpRequest) {
        val request = message.toHostHttpRequest(context.channel())
        val shape = RequestShapeRules.evaluate(request)
        if (shape is RequestShapeRules.Verdict.Reject) {
            releaseProvisionalAdmission(context)
            respond(context, request, shape.toResponse())
            return
        }
        val isChannelUpgrade = RequestShapeRules.isChannelUpgrade(request)
        if (!ensureAdmission(context, isChannelUpgrade)) {
            releaseProvisionalAdmission(context)
            logAdmissionRejection(context, if (isChannelUpgrade) "link_socket" else "ordinary_http")
            reject(context, request, HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, true)
            return
        }
        if (!admissionPolicy.admitRequest(
                source = source(context),
                isLivenessProbe = RequestShapeRules.isLivenessProbe(request),
            )
        ) {
            if (isChannelUpgrade) {
                releaseAdmission(context)
                reject(context, request, HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, true)
            } else {
                reject(context, request, HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED)
            }
            return
        }
        if (isChannelUpgrade) {
            upgradeChannel(context, message)
            return
        }
        if (!requestInFlight.compareAndSet(false, true)) {
            reject(
                context,
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCodes.PIPELINING_NOT_SUPPORTED,
            )
            context.close()
            return
        }
        context.channel().config().isAutoRead = false
        val submitted = dispatchLimiter.trySubmit {
            try {
                respond(context, request, endpoints.handle(request))
            } catch (throwable: Throwable) {
                LOG.warn("Unhandled link request failure", throwable)
                reject(context, request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR)
            }
        }
        if (!submitted) {
            requestInFlight.set(false)
            context.channel().config().isAutoRead = true
            reject(context, request, HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVER_BUSY)
        }
    }

    /**
     * Upgrades to the channel. Nothing is authenticated yet: the first frame must be a
     * [app.tennyo.contract.LinkFrame.Open] whose proof verifies, and the engine closes the socket otherwise.
     */
    private fun upgradeChannel(context: ChannelHandlerContext, message: FullHttpRequest) {
        val handshaker = WebSocketServerHandshakerFactory(
            webSocketLocation(message),
            null,
            false,
            MAX_WEBSOCKET_FRAME_BYTES,
        ).newHandshaker(message)
        if (handshaker == null) {
            releaseAdmission(context)
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel())
            return
        }
        handshaker.handshake(context.channel(), message).addListener { handshake ->
            if (!handshake.isSuccess) {
                releaseAdmission(context)
                context.close()
                return@addListener
            }
            val channel = LinkChannel(NettyLinkSocket(context))
            context.pipeline().remove("idle")
            context.pipeline().remove("idleCloser")
            context.pipeline().addBefore(context.name(), "frames", WebSocketFrameAggregator(MAX_WEBSOCKET_FRAME_BYTES))
            context.pipeline().replace(this, "channel", ChannelFrameHandler(channel) { releaseAdmission(context) })
            val deadline = LinkChannel.OPEN_DEADLINE_MILLIS
            context.executor().schedule(channel::expireUnopened, deadline, TimeUnit.MILLISECONDS)
        }
    }

    private fun webSocketLocation(request: FullHttpRequest): String =
        "wss://${request.headers()[HttpHeaderNames.HOST]}${request.uri()}"

    private fun source(context: ChannelHandlerContext): String =
        (context.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"

    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        context.close()
    }

    private fun reject(
        context: ChannelHandlerContext,
        request: HostHttpRequest,
        status: HttpStatus,
        code: String,
        closeAfterFlush: Boolean = false,
    ) = respond(context, request, transportRejection(status, code, closeAfterFlush))

    /** Writes [response], closing the connection after it when either side asked not to keep it alive. */
    private fun respond(
        context: ChannelHandlerContext,
        request: HostHttpRequest,
        response: HostHttpResponse,
    ) {
        val nettyResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(response.status.code),
            Unpooled.wrappedBuffer(response.body),
        )
        response.headers.forEach { (name, value) -> nettyResponse.headers().add(name, value) }
        HttpUtil.setContentLength(nettyResponse, response.body.size.toLong())
        val keepAlive = request.keepAlive && !response.closeAfterFlush
        HttpUtil.setKeepAlive(nettyResponse, keepAlive)
        val write = context.writeAndFlush(nettyResponse)
        if (!keepAlive) write.addListener { context.close() }
    }

    private fun ensureAdmission(context: ChannelHandlerContext, isChannelUpgrade: Boolean): Boolean {
        if (context.channel().attr(ADMITTED_CHANNEL).get() != null) return true
        val source = source(context)
        val provisionalPermit = context.channel().attr(PROVISIONAL_TLS_PERMIT).get()
        val permit = if (provisionalPermit != null && isChannelUpgrade) {
            admissionPolicy.promoteToLinkSocket(provisionalPermit)
        } else if (provisionalPermit != null) {
            admissionPolicy.promoteToOrdinaryHttp(provisionalPermit)
        } else if (isChannelUpgrade) {
            admissionPolicy.openLinkSocket(source)
        } else {
            admissionPolicy.openOrdinaryHttp(source)
        }
        if (permit != null) context.channel().attr(PROVISIONAL_TLS_PERMIT).set(null)
        context.channel().attr(ADMITTED_CHANNEL).set(permit)
        return permit != null
    }

    private fun releaseAdmission(context: ChannelHandlerContext) {
        context.channel().attr(ADMITTED_CHANNEL).getAndSet(null)?.close()
    }

    private fun releaseProvisionalAdmission(context: ChannelHandlerContext) {
        context.channel().attr(PROVISIONAL_TLS_PERMIT).getAndSet(null)?.let(admissionPolicy::abortTlsHandshake)
    }

    private fun logAdmissionRejection(context: ChannelHandlerContext, stage: String) {
        LOG.info(
            "Tennyo admission rejected stage=$stage source=${source(context)} " +
                    "counters=${admissionPolicy.snapshot()}"
        )
    }

    private companion object {
        val ADMITTED_CHANNEL: AttributeKey<AdmissionPolicy.ChannelPermit> =
            AttributeKey.valueOf("tennyoLink.admitted")

        // A frame holds up to LinkLimits.MAX_FRAME_CHARS UTF-16 units plus a part envelope, at three UTF-8 bytes each.
        const val MAX_WEBSOCKET_FRAME_BYTES = (LinkLimits.MAX_FRAME_CHARS + 1024) * 3
        val LOG = Logger.getInstance(LinkHttpHandler::class.java)
    }
}

/** The request as the protocol engine sees it; the body is copied so the Netty buffer is released as usual. */
internal fun FullHttpRequest.toHostHttpRequest(channel: Channel): HostHttpRequest {
    val decoder = QueryStringDecoder(uri())
    return HostHttpRequest(
        method = method().name(),
        path = decoder.path(),
        rawUri = uri(),
        queryParameters = decoder.parameters(),
        headers = HostHttpHeaders(headers().map { (name, value) -> name to value }),
        body = ByteBufUtil.getBytes(content()),
        protocolVersion = protocolVersion().text(),
        keepAlive = HttpUtil.isKeepAlive(this),
        isSecure = channel.pipeline().get(SslHandler::class.java) != null,
        remoteAddress = (channel.remoteAddress() as? InetSocketAddress)?.address?.hostAddress,
        origin = channel.attr(CONNECTION_ORIGIN).get() ?: ConnectionOrigin.REMOTE,
    )
}

/** Reopens the channel only after the response for the accepted request has been flushed. */
private class HttpResponseGate(
    private val requestInFlight: AtomicBoolean,
) : ChannelDuplexHandler() {
    override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
        if (message !is HttpResponse || !requestInFlight.get()) {
            context.write(message, promise)
            return
        }
        promise.addListener {
            requestInFlight.set(false)
            if (it.isSuccess && context.channel().isActive) {
                context.channel().config().isAutoRead = true
                context.read()
            } else {
                context.close()
            }
        }
        context.write(message, promise)
    }
}

private class NettyLinkSocket(private val context: ChannelHandlerContext) : LinkSocket {
    override fun sendText(text: String, onSendFailed: (Throwable?) -> Unit): Boolean {
        val future = context.channel().writeAndFlush(TextWebSocketFrame(text))
        future.addListener { completed ->
            if (!completed.isSuccess) onSendFailed(completed.cause())
        }
        return !future.isDone || future.isSuccess
    }

    override fun close(code: Int, reason: String) {
        context.channel().writeAndFlush(CloseWebSocketFrame(code, reason)).addListener { context.close() }
    }
}

/** Feeds the socket's text messages to [channel] in order, answers pings, and reports the close. */
private class ChannelFrameHandler(
    private val channel: LinkChannel,
    private val releaseAdmission: () -> Unit,
) : SimpleChannelInboundHandler<WebSocketFrame>() {
    override fun channelRead0(context: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is TextWebSocketFrame -> channel.receive(frame.text())
            is PingWebSocketFrame -> context.writeAndFlush(PongWebSocketFrame(frame.content().retain()))
            is CloseWebSocketFrame -> context.writeAndFlush(frame.retainedDuplicate()).addListener { context.close() }
            else -> Unit
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        channel.closed()
        releaseAdmission()
        context.fireChannelInactive()
    }
}
