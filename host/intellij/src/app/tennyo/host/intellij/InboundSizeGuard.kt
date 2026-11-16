package app.tennyo.host.intellij

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.ErrorResponse
import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.rpc.hostJson
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http.QueryStringDecoder
import java.nio.charset.StandardCharsets

/** Counts chunks before aggregation, so a request cannot make the listener buffer more than its endpoint accepts. */
internal class InboundSizeGuard : ChannelInboundHandlerAdapter() {
    private var limit = 0
    private var received = 0
    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        if (message is HttpRequest) {
            limit = LinkEndpoints.requestBodyLimitBytes(QueryStringDecoder(message.uri()).path())
            received = 0
        }
        if (message is HttpContent) {
            received += message.content().readableBytes()
            if (received > limit) {
                message.release()
                val bytes = hostJson(
                    ErrorResponse(
                        error = ErrorCodes.REQUEST_TOO_LARGE,
                        message = "The request body exceeds the limit for this endpoint.",
                    )
                ).toByteArray(StandardCharsets.UTF_8)
                val response = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    Unpooled.wrappedBuffer(bytes),
                )
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                HttpUtil.setContentLength(response, bytes.size.toLong())
                context.writeAndFlush(
                    response
                ).addListener { context.close() }
                return
            }
            if (message is LastHttpContent) received = 0
        }
        context.fireChannelRead(message)
    }
}
