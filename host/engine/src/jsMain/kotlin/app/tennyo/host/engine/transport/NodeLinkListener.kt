package app.tennyo.host.engine.transport

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.ErrorResponse
import app.tennyo.contract.LinkEndpoints
import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.asByteArray
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.rpc.PublicEndpoints
import app.tennyo.host.engine.rpc.hostJson
import kotlinx.coroutines.CoroutineScope
import org.khronos.webgl.Uint8Array

/**
 * The Node side of the listener: it mirrors the IntelliJ Netty pipeline with the same shared policies, so both hosts
 * answer [TransportConformanceCases] identically. Node's own HTTP parser refuses some malformed framing before this
 * code sees it, so [clientError] answers those with the status and code the shape rules would have produced.
 *
 * [gate] decides each connection when it is accepted: a remote source is destroyed before any TLS bytes are read while
 * Remote access is off.
 */
class NodeLinkListener(
    private val identity: NodeTransportIdentity,
    scope: CoroutineScope,
    private val gate: OriginGate,
    private val admissionPolicy: AdmissionPolicy = AdmissionPolicy(),
    private val dispatchLimiter: DispatchLimiter = DispatchLimiter(scope),
    discoveryRoutes: DiscoveryRoutes? = null,
) {
    private val endpoints = PublicEndpoints(discoveryRoutes)
    private val channels = NodeChannelUpgrades()
    private var server: NodeHttpsServer? = null
    private val connections = mutableSetOf<NodeSocket>()
    private val remoteConnections = mutableSetOf<NodeSocket>()

    /**
     * Binds [host]:[port]; port 0 asks the OS for one, and [onBound] is told which it got.
     * [onFailed] gets the bind error (a port another process holds, say) instead of a crash.
     */
    fun listen(host: String, port: Int, onFailed: (String) -> Unit = {}, onBound: (Int) -> Unit) {
        val created = NodeHttps.createServer(
            jsOptions(
                "cert" to identity.certificatePem,
                "key" to identity.privateKeyPem,
                "minVersion" to "TLSv1.2",
                // The IntelliJ listener's TLS handshake timeout; Node's own default holds a silent socket for 120 s.
                "handshakeTimeout" to TLS_HANDSHAKE_TIMEOUT_MILLIS,
            ),
        )
        // Node answers a missing Host itself, with its own body. The shape rules own that verdict on every host, so
        // the request is let through and rejected here instead.
        created.asDynamic().requireHostHeader = false
        // `connection` fires on the raw TCP socket, before the TLS handshake, so a refused source costs no handshake.
        created.on("connection") { socket: NodeSocket -> refuseRemoteSource(socket) }
        created.on("request") { request: NodeIncomingMessage, response: NodeServerResponse ->
            handle(request, response)
        }
        created.on("upgrade") { request: NodeIncomingMessage, socket: NodeSocket, head: Uint8Array ->
            upgrade(request, socket, head)
        }
        created.on("clientError") { _: dynamic, socket: NodeSocket -> clientError(socket) }
        // A plain-HTTP or wrong-protocol client is dropped without an answer, which is what destroying it does.
        // Logged at info: a phone that refuses this certificate (a stale pin) shows up here and nowhere else.
        created.on("tlsClientError") { error: dynamic, socket: NodeSocket ->
            LOG.info("TLS handshake failed: ${error?.code ?: error?.message}")
            socket.destroy()
        }
        created.asDynamic().once("error") { error: dynamic -> onFailed("${error?.code ?: error?.message}") }
        created.listen(port, host) { onBound(created.address().port as Int) }
        server = created
    }

    private fun refuseRemoteSource(socket: NodeSocket) {
        val origin = gate.originOf(socket.remoteAddress)
        if (!gate.admits(origin)) {
            LOG.info("Refused a remote source before TLS: ${socket.source()}")
            socket.destroy()
            return
        }
        connections += socket
        if (origin == ConnectionOrigin.REMOTE) remoteConnections += socket
        socket.on("close") {
            connections -= socket
            remoteConnections -= socket
        }
    }

    /** Ends every open connection that came from a remote origin, for when Remote access is switched off. */
    fun closeRemoteConnections() {
        remoteConnections.toList().forEach { it.destroy() }
    }

    fun close(onClosed: () -> Unit = {}) {
        connections.toList().forEach { it.destroy() }
        server?.close(onClosed) ?: onClosed()
        server = null
    }

    private fun handle(request: NodeIncomingMessage, response: NodeServerResponse) {
        val socket = response.socket ?: request.socket
        val limit = LinkEndpoints.requestBodyLimitBytes(request.url.substringBefore('?'))
        // The IntelliJ InboundSizeGuard answers before aggregating; refusing on the declared length is the same
        // promise without buffering the body first.
        if (request.rawHeaders.headerValues(HostHttpHeaderNames.CONTENT_LENGTH).any { it.exceeds(limit) }) {
            socket.writeRaw(oversizedBody(), close = true)
            return
        }
        request.readBody(limit) { body, overflowed ->
            if (overflowed) {
                socket.writeRaw(oversizedBody(), close = true)
            } else {
                dispatch(request.asHost(body, gate), socket)
            }
        }
    }

    private fun dispatch(request: HostHttpRequest, socket: NodeSocket) {
        val shape = RequestShapeRules.evaluate(request)
        if (shape is RequestShapeRules.Verdict.Reject) {
            socket.respond(request, shape.toResponse())
            return
        }
        if (!socket.admitConnection { admissionPolicy.openOrdinaryHttp(socket.source()) }) {
            socket.respond(request, closing(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED))
            return
        }
        if (!socket.claimForRequest()) {
            // Netty decodes both pipelined requests before it can stop reading, and answers the second one this way.
            socket.writeRaw(closing(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.PIPELINING_NOT_SUPPORTED), true)
            return
        }
        if (!admissionPolicy.admitRequest(socket.source(), RequestShapeRules.isLivenessProbe(request))) {
            socket.respond(request, open(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED))
            return
        }
        val submitted = dispatchLimiter.trySubmit {
            val answer = runCatching { endpoints.handle(request) }
                .getOrElse { open(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR) }
            socket.respond(request, answer)
        }
        if (!submitted) {
            socket.respond(request, open(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVER_BUSY))
        }
    }

    /**
     * A WebSocket upgrade of the channel path. Nothing is authenticated yet: the engine reads the first frame and
     * closes the socket when its proof does not verify, exactly as on the JVM.
     */
    private fun upgrade(request: NodeIncomingMessage, socket: NodeSocket, head: Uint8Array) {
        val hostRequest = request.asHost(ByteArray(0), gate)
        if (!RequestShapeRules.isChannelUpgrade(hostRequest) ||
            RequestShapeRules.evaluate(hostRequest) is RequestShapeRules.Verdict.Reject
        ) {
            socket.destroy()
            return
        }
        val admitted = socket.admitConnection { admissionPolicy.openLinkSocket(socket.source()) } &&
                admissionPolicy.admitRequest(socket.source(), isLivenessProbe = false)
        if (!admitted) {
            socket.writeRaw(closing(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED), close = true)
            return
        }
        channels.accept(request, socket, head)
    }

    /** Node's parser refused the framing; the shape rules would have called the same request malformed. */
    private fun clientError(socket: NodeSocket) =
        socket.writeRaw(closing(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST), close = true)

    /** A rejection that hangs up, for the cases where the IntelliJ listener also closes the connection. */
    private fun closing(status: HttpStatus, code: String): HostHttpResponse =
        transportRejection(status, code, closeAfterFlush = true)

    /** A rejection that leaves the connection usable, which is what the IntelliJ listener does for a busy host. */
    private fun open(status: HttpStatus, code: String): HostHttpResponse = transportRejection(status, code)

    private fun oversizedBody(): HostHttpResponse = HostHttpResponse(
        status = HttpStatus.REQUEST_ENTITY_TOO_LARGE,
        headers = listOf(HostHttpHeaderNames.CONTENT_TYPE to JSON_CONTENT_TYPE),
        body = hostJson(
            ErrorResponse(ErrorCodes.REQUEST_TOO_LARGE, "The request body exceeds the limit for this endpoint."),
        ).encodeToByteArray(),
        closeAfterFlush = true,
    )

    private fun NodeSocket.respond(request: HostHttpRequest, response: HostHttpResponse) {
        val keepAlive = request.keepAlive && !response.closeAfterFlush
        writeRaw(response, close = !keepAlive)
        if (keepAlive) releaseRequest()
    }
}

/**
 * Node's response object reorders answers to match request order, which would hide a pipelining rejection behind the
 * answer to the request that preceded it. Writing the response bytes to the socket keeps the order the wire saw.
 */
private fun NodeSocket.writeRaw(response: HostHttpResponse, close: Boolean) {
    val head = buildString {
        append("HTTP/1.1 ${response.status.code} ${response.status.reasonPhrase()}\r\n")
        response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
        append("${HostHttpHeaderNames.CONTENT_LENGTH}: ${response.body.size}\r\n")
        append("connection: ${if (close) "close" else "keep-alive"}\r\n\r\n")
    }
    val message = head + response.body.decodeToString()
    // `end` rather than `write` + `destroy`: destroying a TLS socket discards whatever has not been flushed yet.
    if (close) end(message) else write(message)
}

/** The reason phrases for the statuses the transport sends; a raw write gets none from Node. */
private fun HttpStatus.reasonPhrase(): String = when (this) {
    HttpStatus.OK -> "OK"
    HttpStatus.NOT_MODIFIED -> "Not Modified"
    HttpStatus.BAD_REQUEST -> "Bad Request"
    HttpStatus.UNAUTHORIZED -> "Unauthorized"
    HttpStatus.FORBIDDEN -> "Forbidden"
    HttpStatus.NOT_FOUND -> "Not Found"
    HttpStatus.METHOD_NOT_ALLOWED -> "Method Not Allowed"
    HttpStatus.REQUEST_TIMEOUT -> "Request Timeout"
    HttpStatus.CONFLICT -> "Conflict"
    HttpStatus.GONE -> "Gone"
    HttpStatus.REQUEST_ENTITY_TOO_LARGE -> "Payload Too Large"
    HttpStatus.UPGRADE_REQUIRED -> "Upgrade Required"
    HttpStatus.TOO_MANY_REQUESTS -> "Too Many Requests"
    HttpStatus.SERVICE_UNAVAILABLE -> "Service Unavailable"
    else -> "Internal Server Error"
}

private fun String.exceeds(limit: Int): Boolean = (toIntOrNull() ?: 0) > limit

private const val IPV4_MAPPED_PREFIX = "::ffff:"
private const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000

private fun NodeSocket.source(): String = remoteAddress?.removePrefix(IPV4_MAPPED_PREFIX) ?: "unknown"

/**
 * Takes [permit] for this connection and holds it until the connection closes. A connection that already holds one
 * keeps it, so a keep-alive connection is counted once however many requests it carries, as on the JVM.
 */
private fun NodeSocket.admitConnection(open: () -> AdmissionPolicy.ChannelPermit?): Boolean {
    val self = asDynamic()
    if (self.tennyoPermit != null && self.tennyoPermit != undefined) return true
    val permit = open() ?: return false
    self.tennyoPermit = permit
    on("close") { permit.close() }
    return true
}

/** One request at a time per connection, which is how the JVM listener refuses pipelining. */
private fun NodeSocket.claimForRequest(): Boolean {
    val self = asDynamic()
    if (self.tennyoRequestInFlight == true) return false
    self.tennyoRequestInFlight = true
    return true
}

private fun NodeSocket.releaseRequest() {
    asDynamic().tennyoRequestInFlight = false
}

/** Reads the body, stopping as soon as more than [limit] bytes have arrived. */
private fun NodeIncomingMessage.readBody(limit: Int, onComplete: (ByteArray, Boolean) -> Unit) {
    val chunks = mutableListOf<ByteArray>()
    var received = 0
    var overflowed = false
    on("data") { chunk: Uint8Array ->
        received += chunk.length
        if (received > limit) overflowed = true else chunks += chunk.asByteArray()
    }
    on("end") { onComplete(chunks.concatenate(), overflowed) }
}

private fun List<ByteArray>.concatenate(): ByteArray = when (size) {
    0 -> ByteArray(0)
    1 -> first()
    else -> {
        val total = ByteArray(sumOf { it.size })
        var offset = 0
        forEach { chunk ->
            chunk.copyInto(total, offset)
            offset += chunk.size
        }
        total
    }
}

/** Node's `rawHeaders` is a flat name/value list, which keeps the repeated headers the shape rules must see. */
private fun Array<String>.headerPairs(): List<Pair<String, String>> =
    (indices step 2).mapNotNull { index -> getOrNull(index + 1)?.let { this[index] to it } }

private fun Array<String>.headerValues(name: String): List<String> =
    headerPairs().filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

internal fun NodeIncomingMessage.asHost(body: ByteArray, gate: OriginGate): HostHttpRequest {
    val headers = rawHeaders.headerPairs()
    return HostHttpRequest(
        method = method,
        path = url.substringBefore('?').decodeUriComponent(),
        rawUri = url,
        queryParameters = url.substringAfter('?', "").parseQuery(),
        headers = HostHttpHeaders(headers),
        body = body,
        protocolVersion = "HTTP/$httpVersion",
        keepAlive = headers.none { it.first.equals("connection", true) && it.second.equals("close", true) },
        isSecure = true,
        remoteAddress = socket.remoteAddress?.removePrefix(IPV4_MAPPED_PREFIX),
        origin = gate.originOf(socket.remoteAddress),
    )
}

private fun String.parseQuery(): Map<String, List<String>> =
    split('&')
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=').decodeUriComponent() to it.substringAfter('=', "").decodeUriComponent() }
        .groupBy({ it.first }, { it.second })

private fun String.decodeUriComponent(): String =
    runCatching { decodeURIComponent(replace("+", " ")) }.getOrDefault(this)

private external fun decodeURIComponent(encoded: String): String

private val LOG = HostLogger("NodeLinkListener")
