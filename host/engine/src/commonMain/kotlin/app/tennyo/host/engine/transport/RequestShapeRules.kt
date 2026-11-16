package app.tennyo.host.engine.transport

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.ErrorResponse
import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.rpc.hostJson

/**
 * The request-shape gate every listener applies before admission. It rejects anything outside the explicit API, any
 * encoded body, and any request whose framing could be read two ways, so smuggling attempts never reach protocol code.
 */
object RequestShapeRules {
    const val MAX_URI_LENGTH = 4 * 1024

    sealed interface Verdict {
        data object Accept : Verdict

        data class Reject(val status: HttpStatus, val errorCode: String, val closeAfterFlush: Boolean) : Verdict
    }

    private val outsideApi = Verdict.Reject(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, true)
    private val malformed = Verdict.Reject(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, true)

    fun evaluate(request: HostHttpRequest): Verdict {
        val path = request.path
        val headers = request.headers
        val outside = !path.startsWith("${LinkEndpoints.PREFIX}/")
        val encoded = headers[HostHttpHeaderNames.CONTENT_ENCODING]?.let { it != "identity" } == true
        if (outside || encoded) return outsideApi
        val ambiguous = request.protocolVersion != HostHttpRequest.HTTP_1_1 ||
                HostHttpHeaderNames.TRANSFER_ENCODING in headers &&
                HostHttpHeaderNames.CONTENT_LENGTH in headers ||
                request.rawUri.length > MAX_URI_LENGTH ||
                headers.getAll(HostHttpHeaderNames.HOST).size != 1 ||
                headers.getAll(HostHttpHeaderNames.CONTENT_LENGTH).distinct().size > 1
        return if (ambiguous) malformed else Verdict.Accept
    }

    /** A WebSocket upgrade of the channel, which admission counts separately from ordinary HTTP. */
    fun isChannelUpgrade(request: HostHttpRequest): Boolean =
        request.path == LinkEndpoints.CHANNEL &&
                "websocket".equals(request.headers[HostHttpHeaderNames.UPGRADE], ignoreCase = true)

    /** The hello, which admission lets through a rate limit that would stop other requests. */
    fun isLivenessProbe(request: HostHttpRequest): Boolean = request.path == LinkEndpoints.HELLO
}

/** The JSON rejection the transport sends; its message is deliberately the same for every rejection. */
fun transportRejection(
    status: HttpStatus,
    errorCode: String,
    closeAfterFlush: Boolean = false,
): HostHttpResponse =
    HostHttpResponse(
        status = status,
        headers = listOf(HostHttpHeaderNames.CONTENT_TYPE to JSON_CONTENT_TYPE),
        body = hostJson(ErrorResponse(errorCode, "Request rejected.")).encodeToByteArray(),
        closeAfterFlush = closeAfterFlush,
    )

fun RequestShapeRules.Verdict.Reject.toResponse(): HostHttpResponse =
    transportRejection(status, errorCode, closeAfterFlush)

const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
