package app.tennyo.host.engine.transport

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.LinkEndpoints

/**
 * One raw exchange every companion listener must answer the same way.
 *
 * [rawRequest] is written to a fresh connection exactly as given, over TLS unless [overTls] is `false`.
 * [expectedStatus] is the status of the first response on the wire, or `null` when the listener must drop the
 * connection without answering. [closesConnection] says whether the listener closes the connection after that.
 */
data class TransportConformanceCase(
    val name: String,
    val rawRequest: String,
    val expectedStatus: Int?,
    val expectedErrorCode: String?,
    val closesConnection: Boolean,
    val overTls: Boolean = true,
)

/**
 * The transport rules as data, so the IntelliJ listener and later listeners run the same cases.
 *
 * Two [RequestShapeRules] checks have no case here because the HTTP parser answers first and parsers differ: an
 * overlong URI trips the parser's request-line limit, and a request with both `transfer-encoding` and
 * `content-length` is normalised or refused by the parser. `RequestShapeRulesTest` covers both rules directly.
 */
object TransportConformanceCases {
    private const val CRLF = "\r\n"
    private const val HOST = "host: companion.test$CRLF"
    private const val HELLO = LinkEndpoints.HELLO
    private const val PAIR = LinkEndpoints.PAIR

    private fun get(path: String, headers: String = HOST, version: String = HostHttpRequest.HTTP_1_1): String =
        "GET $path $version$CRLF$headers$CRLF"

    private fun post(path: String, body: String, headers: String = HOST): String =
        "POST $path ${HostHttpRequest.HTTP_1_1}$CRLF${headers}content-length: ${body.length}$CRLF$CRLF$body"

    private fun rejected(
        name: String,
        rawRequest: String,
        status: HttpStatus,
        errorCode: String,
        closesConnection: Boolean = true,
    ) = TransportConformanceCase(name, rawRequest, status.code, errorCode, closesConnection)

    val all: List<TransportConformanceCase> = listOf(
        rejected(
            name = "path outside the API",
            rawRequest = get("/index.html"),
            status = HttpStatus.NOT_FOUND,
            errorCode = ErrorCodes.NOT_FOUND,
        ),
        rejected(
            name = "encoded body",
            rawRequest = post(PAIR, "{}", headers = "${HOST}content-encoding: gzip$CRLF"),
            status = HttpStatus.NOT_FOUND,
            errorCode = ErrorCodes.NOT_FOUND,
        ),
        rejected(
            name = "HTTP/1.0",
            rawRequest = get(HELLO, version = "HTTP/1.0"),
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCodes.INVALID_REQUEST,
        ),
        rejected(
            name = "missing host",
            rawRequest = get(HELLO, headers = ""),
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCodes.INVALID_REQUEST,
        ),
        rejected(
            name = "repeated host",
            rawRequest = get(HELLO, headers = "$HOST$HOST"),
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCodes.INVALID_REQUEST,
        ),
        rejected(
            name = "conflicting content length",
            rawRequest = "POST $PAIR ${HostHttpRequest.HTTP_1_1}$CRLF${HOST}content-length: 2$CRLF" +
                    "content-length: 3$CRLF$CRLF{} ",
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCodes.INVALID_REQUEST,
        ),
        rejected(
            name = "pipelined request",
            rawRequest = get(HELLO) + get(HELLO),
            status = HttpStatus.TOO_MANY_REQUESTS,
            errorCode = ErrorCodes.PIPELINING_NOT_SUPPORTED,
        ),
        rejected(
            name = "body over the endpoint limit",
            rawRequest = post(PAIR, "x".repeat(LinkEndpoints.PAIR_BODY_LIMIT_BYTES + 1)),
            status = HttpStatus.REQUEST_ENTITY_TOO_LARGE,
            errorCode = ErrorCodes.REQUEST_TOO_LARGE,
        ),
        TransportConformanceCase(
            name = "plain HTTP to the TLS listener",
            rawRequest = get(HELLO),
            expectedStatus = null,
            expectedErrorCode = null,
            closesConnection = true,
            overTls = false,
        ),
        rejected(
            name = "channel without a WebSocket upgrade",
            rawRequest = get(LinkEndpoints.CHANNEL),
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCodes.INVALID_REQUEST,
            closesConnection = false,
        ),
    )
}
