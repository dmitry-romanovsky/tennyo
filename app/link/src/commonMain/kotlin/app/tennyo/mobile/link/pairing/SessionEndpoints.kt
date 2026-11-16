package app.tennyo.mobile.link.pairing

import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.SessionClaimRequest
import app.tennyo.contract.SessionResponse
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.transport.LinkHttp
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/** The public endpoints a session starts with: finding a reachable host and claiming a session on it. */
class SessionEndpoints(
    private val http: LinkHttp,
    private val deviceIdentity: DeviceIdentity,
    private val rememberedPairs: RememberedPairs,
    private val knownHosts: KnownHosts,
    private val clientVersion: String,
) {
    /**
     * Tries the ticket's addresses in order and returns the first that answers the hello, as a ticket pointing at
     * it. Loopback-only tickets fail up front: the phone can never reach them.
     */
    suspend fun hello(ticket: LinkTicket): HelloResult {
        val candidates = ticket.endpointCandidates()
        if (candidates.isEmpty()) throw LinkFault.Unreachable(LinkFault.UnreachableReason.LocalOnlyAddresses)
        var lastFailure: Throwable? = null
        candidates.forEach { endpoint ->
            val candidate = ticket.withEndpoint(endpoint)
            runCatching { withTimeout(HELLO_ATTEMPT_TIMEOUT_MILLIS.milliseconds) { helloAt(candidate) } }
                .onSuccess { response -> return HelloResult(candidate, response) }
                .onFailure { lastFailure = it }
        }
        throw lastFailure ?: LinkFault.Unreachable(LinkFault.UnreachableReason.NoEndpoints)
    }

    private suspend fun helloAt(ticket: LinkTicket): HandshakeResponse =
        http.hello(ticket).also { response -> knownHosts.remember(ticket, response) }

    /** Asks the IDE to admit this device, naming the remembered pair it may resume with. */
    suspend fun claim(ticket: LinkTicket): SessionResponse {
        val identity = deviceIdentity.snapshot()
        val rememberedPair = rememberedPairs.loadForClaim(ticket)
        val request = PairRequest.Claim(
            SessionClaimRequest(
                sessionId = ticket.sessionId,
                clientName = identity.clientName,
                clientVersion = clientVersion,
                clientId = identity.clientId,
                devicePublicKey = identity.publicKeyBase64,
                deviceFingerprint = identity.fingerprint,
                rememberedPairId = rememberedPair?.pairId,
            ),
        )
        // A failed or cancelled claim releases the pair it announced as surely as one that needed no resume.
        return runCatching {
            http.pair(ticket, request) { LinkJson.decodeFromString(SessionResponse.serializer(), it) }
        }
            .onFailure { rememberedPairs.releaseClaim(ticket) }
            .getOrThrow()
            .also { response ->
                knownHosts.remember(ticket, response)
                if (!response.requiresPairingConfirmation) rememberedPairs.releaseClaim(ticket)
            }
    }
}

/** The hello's answer and the ticket re-pointed at the endpoint that gave it. */
data class HelloResult(
    val ticket: LinkTicket,
    val response: HandshakeResponse,
)

internal const val HELLO_ATTEMPT_TIMEOUT_MILLIS = 4_000L
