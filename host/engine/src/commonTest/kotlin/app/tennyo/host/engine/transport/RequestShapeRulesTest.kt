package app.tennyo.host.engine.transport

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.LinkEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestShapeRulesTest {
    private val notFound =
        RequestShapeRules.Verdict.Reject(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, true)
    private val invalid =
        RequestShapeRules.Verdict.Reject(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, true)

    @Test
    fun `well formed API request is accepted`() {
        assertEquals(RequestShapeRules.Verdict.Accept, RequestShapeRules.evaluate(request()))
    }

    @Test
    fun `path outside the API is not found`() {
        assertEquals(notFound, RequestShapeRules.evaluate(request(path = "/index.html")))
        assertEquals(notFound, RequestShapeRules.evaluate(request(path = "${LinkEndpoints.PREFIX}x/link/hello")))
        assertEquals(notFound, RequestShapeRules.evaluate(request(path = LinkEndpoints.PREFIX)))
    }

    @Test
    fun `encoded body is not found`() {
        assertEquals(notFound, RequestShapeRules.evaluate(request(extraHeaders = listOf("Content-Encoding" to "gzip"))))
        assertEquals(
            RequestShapeRules.Verdict.Accept,
            RequestShapeRules.evaluate(request(extraHeaders = listOf("content-encoding" to "identity"))),
        )
    }

    @Test
    fun `protocol other than HTTP 1_1 is invalid`() {
        assertEquals(invalid, RequestShapeRules.evaluate(request(protocolVersion = "HTTP/1.0")))
    }

    @Test
    fun `transfer encoding with content length is invalid`() {
        val headers = listOf("Transfer-Encoding" to "chunked", "Content-Length" to "2")

        assertEquals(invalid, RequestShapeRules.evaluate(request(extraHeaders = headers)))
    }

    @Test
    fun `URI over the limit is invalid`() {
        val atLimit = "${LinkEndpoints.PREFIX}/".padEnd(RequestShapeRules.MAX_URI_LENGTH, 'a')

        assertEquals(RequestShapeRules.Verdict.Accept, RequestShapeRules.evaluate(request(rawUri = atLimit)))
        assertEquals(invalid, RequestShapeRules.evaluate(request(rawUri = atLimit + "a")))
    }

    @Test
    fun `host must appear exactly once`() {
        assertEquals(invalid, RequestShapeRules.evaluate(request(host = null)))
        assertEquals(invalid, RequestShapeRules.evaluate(request(extraHeaders = listOf("HOST" to "other.test"))))
    }

    @Test
    fun `conflicting content lengths are invalid`() {
        val conflicting = listOf("Content-Length" to "2", "content-length" to "3")
        val repeated = listOf("Content-Length" to "2", "content-length" to "2")

        assertEquals(invalid, RequestShapeRules.evaluate(request(extraHeaders = conflicting)))
        assertEquals(RequestShapeRules.Verdict.Accept, RequestShapeRules.evaluate(request(extraHeaders = repeated)))
    }

    @Test
    fun `channel upgrade and liveness probe are recognised by path`() {
        val channel = request(
            path = LinkEndpoints.CHANNEL,
            extraHeaders = listOf("Upgrade" to "WebSocket"),
        )

        assertTrue(RequestShapeRules.isChannelUpgrade(channel))
        assertFalse(RequestShapeRules.isChannelUpgrade(request(path = channel.path)))
        assertTrue(RequestShapeRules.isLivenessProbe(request(path = LinkEndpoints.HELLO)))
        assertFalse(RequestShapeRules.isLivenessProbe(request(path = LinkEndpoints.PAIR)))
    }

    @Test
    fun `transport rejection carries the code and closes when asked`() {
        val response = invalid.toResponse()

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertTrue(response.closeAfterFlush)
        assertEquals(
            """{"error":"invalid_request","message":"Request rejected."}""",
            response.body.decodeToString(),
        )
    }

    private fun request(
        path: String = LinkEndpoints.HELLO,
        rawUri: String = path,
        protocolVersion: String = HostHttpRequest.HTTP_1_1,
        host: String? = "companion.test",
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) = HostHttpRequest(
        method = "GET",
        path = path,
        rawUri = rawUri,
        headers = HostHttpHeaders(listOfNotNull(host?.let { "Host" to it }) + extraHeaders),
        protocolVersion = protocolVersion,
        origin = ConnectionOrigin.LOCAL,
    )
}
