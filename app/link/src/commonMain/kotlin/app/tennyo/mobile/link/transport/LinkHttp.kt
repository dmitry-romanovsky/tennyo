package app.tennyo.mobile.link.transport

import app.tennyo.contract.DeviceProof
import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.SignedPairRequest
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.proof
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlin.time.Clock

/**
 * The link's three public HTTPS endpoints. Everything after pairing runs over [LinkChannels]; this only reaches a host,
 * pairs with it, and finds it on the local network. Pairing requests carry a [DeviceProof] over their exact JSON text.
 */
class LinkHttp(
    internal val httpClient: HttpClient,
    private val deviceIdentity: DeviceIdentity,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun hello(ticket: LinkTicket): HandshakeResponse {
        requirePinnedTransport(ticket)
        val text = httpClient.get(ticket.linkUrl(LinkEndpoints.HELLO)) {
            header(HttpHeaders.Authorization, "$BEARER_PREFIX${ticket.token}")
            parameter(LinkEndpoints.SESSION_PARAMETER, ticket.sessionId)
        }.bodyAsText()
        return LinkJson.decodeFromString(HandshakeResponse.serializer(), text)
    }

    /** Sends [request] for [ticket]'s session, signed by this device, and decodes the answer as [T]. */
    suspend fun <T> pair(
        ticket: LinkTicket,
        request: PairRequest,
        decode: (String) -> T,
    ): T {
        requirePinnedTransport(ticket)
        val requestText = LinkJson.encodeToString(PairRequest.serializer(), request)
        val body = SignedPairRequest(
            sessionId = ticket.sessionId,
            request = requestText,
            proof = deviceIdentity.proof(ticket, LinkEndpoints.PAIR, requestText, now()),
        )
        val text = httpClient.post(ticket.linkUrl(LinkEndpoints.PAIR)) {
            header(HttpHeaders.Authorization, "$BEARER_PREFIX${ticket.token}")
            setBody(jsonBody(LinkJson.encodeToString(SignedPairRequest.serializer(), body)))
        }.bodyAsText()
        return decode(text)
    }

    /** Asks the host behind [ticket]'s endpoint about a remembered pair; the answer is decoded as [T]. */
    suspend fun <T> discover(ticket: LinkTicket, request: DiscoverRequest, decode: (String) -> T): T {
        requirePinnedTransport(ticket)
        val text = httpClient.post(ticket.linkUrl(LinkEndpoints.DISCOVER)) {
            setBody(jsonBody(LinkJson.encodeToString(DiscoverRequest.serializer(), request)))
        }.bodyAsText()
        return decode(text)
    }

    /** A proof over [requestText] for a discovery connect, whose "session" is the connect challenge. */
    internal suspend fun discoveryProof(ticket: LinkTicket, requestText: String): DeviceProof =
        deviceIdentity.proof(ticket, LinkEndpoints.DISCOVER, requestText, now())

    private fun jsonBody(text: String) = TextContent(text, ContentType.Application.Json)

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}

/** [path] on the host this ticket points at. A ticket may carry a path after the authority; only its origin is used. */
fun LinkTicket.linkUrl(path: String, scheme: String = "https"): String {
    val authority = endpoint.substringAfter("://").substringBefore('/')
    return "$scheme://$authority$path"
}
