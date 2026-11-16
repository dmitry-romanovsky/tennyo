package app.tennyo.host.intellij.tests

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.intellij.InboundSizeGuard
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class InboundSizeGuardTest {
    @Test
    fun `rejects any body on the bodyless endpoints before aggregation`() {
        assertBodyIsRejected(HttpMethod.GET, LinkEndpoints.HELLO)
        assertBodyIsRejected(HttpMethod.GET, LinkEndpoints.CHANNEL)
    }

    private fun assertBodyIsRejected(method: HttpMethod, path: String) {
        val channel = EmbeddedChannel(InboundSizeGuard())

        channel.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path))
        channel.writeInbound(DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(1))))

        val response = channel.readOutbound<FullHttpResponse>()
        try {
            assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
            assertEquals(
                ErrorCodes.REQUEST_TOO_LARGE,
                response.content().toString(StandardCharsets.UTF_8).substringAfter("\"error\":\"").substringBefore('"'),
            )
        } finally {
            response.release()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `rejects an oversized discovery payload before aggregation`() {
        val channel = EmbeddedChannel(InboundSizeGuard())
        val path = LinkEndpoints.DISCOVER

        channel.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path))
        channel.writeInbound(
            DefaultLastHttpContent(
                Unpooled.wrappedBuffer(ByteArray(LinkEndpoints.DISCOVER_BODY_LIMIT_BYTES + 1)),
            ),
        )

        val response = channel.readOutbound<FullHttpResponse>()
        try {
            assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
            assertEquals(
                ErrorCodes.REQUEST_TOO_LARGE,
                response.content().toString(StandardCharsets.UTF_8).substringAfter("\"error\":\"").substringBefore('"'),
            )
        } finally {
            response.release()
            channel.finishAndReleaseAll()
        }
    }
}
