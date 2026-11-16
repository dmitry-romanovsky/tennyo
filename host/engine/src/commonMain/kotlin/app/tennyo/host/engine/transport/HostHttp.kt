package app.tennyo.host.engine.transport

import kotlin.jvm.JvmInline

/**
 * Header names the transport reads or writes. They are lower case because that is what the IntelliJ listener has
 * always put on the wire; HTTP compares header names case-insensitively either way.
 */
object HostHttpHeaderNames {
    const val ALLOW = "allow"
    const val AUTHORIZATION = "authorization"
    const val CONTENT_ENCODING = "content-encoding"
    const val CONTENT_LENGTH = "content-length"
    const val CONTENT_TYPE = "content-type"
    const val HOST = "host"
    const val TRANSFER_ENCODING = "transfer-encoding"
    const val UPGRADE = "upgrade"
}

/** An HTTP status the host sends. Each listener writes the platform's standard reason phrase for [code]. */
@JvmInline
value class HttpStatus(val code: Int) {
    companion object {
        val OK = HttpStatus(200)
        val NOT_MODIFIED = HttpStatus(304)
        val BAD_REQUEST = HttpStatus(400)
        val UNAUTHORIZED = HttpStatus(401)
        val FORBIDDEN = HttpStatus(403)
        val NOT_FOUND = HttpStatus(404)
        val METHOD_NOT_ALLOWED = HttpStatus(405)
        val REQUEST_TIMEOUT = HttpStatus(408)
        val CONFLICT = HttpStatus(409)
        val GONE = HttpStatus(410)
        val REQUEST_ENTITY_TOO_LARGE = HttpStatus(413)
        val UPGRADE_REQUIRED = HttpStatus(426)
        val TOO_MANY_REQUESTS = HttpStatus(429)
        val INTERNAL_SERVER_ERROR = HttpStatus(500)
        val SERVICE_UNAVAILABLE = HttpStatus(503)
    }
}

/** Request headers in arrival order, looked up case-insensitively and allowed to repeat. */
class HostHttpHeaders(private val entries: List<Pair<String, String>> = emptyList()) {
    /** The first value of [name], or `null` when the header is absent. */
    operator fun get(name: String): String? = entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun getAll(name: String): List<String> =
        entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    operator fun contains(name: String): Boolean = entries.any { it.first.equals(name, ignoreCase = true) }
}

/**
 * One complete HTTP request as a listener hands it to the protocol engine.
 *
 * [path] is the percent-decoded path and [queryParameters] the decoded query, exactly as the listener's URI decoder
 * produced them; [rawUri] is the request target as received. [isSecure] reports whether the listener terminated TLS
 * for this request, and [remoteAddress] is the peer's bare IP address, used in diagnostics and admission. [origin] is
 * decided once per connection, when the listener accepts it.
 */
class HostHttpRequest(
    val method: String,
    val path: String,
    val rawUri: String = path,
    val queryParameters: Map<String, List<String>> = emptyMap(),
    val headers: HostHttpHeaders = HostHttpHeaders(),
    val body: ByteArray = ByteArray(0),
    val protocolVersion: String = HTTP_1_1,
    val keepAlive: Boolean = true,
    val isSecure: Boolean = true,
    val remoteAddress: String? = null,
    val origin: ConnectionOrigin,
) {
    /** The body decoded as UTF-8, with malformed sequences replaced. */
    val bodyText: String by lazy { body.decodeToString() }

    fun firstQueryParameter(name: String): String? = queryParameters[name]?.firstOrNull()

    companion object {
        const val HTTP_1_1 = "HTTP/1.1"
    }
}

/**
 * One complete HTTP response. The listener adds `content-length` and keep-alive handling; a response with
 * [closeAfterFlush] never keeps the connection open.
 */
class HostHttpResponse(
    val status: HttpStatus,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val closeAfterFlush: Boolean = false,
)
