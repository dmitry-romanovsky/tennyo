package app.tennyo.host.engine.rpc

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.ErrorResponse
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.PairingConfirmationResponse
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.contract.SignedPairRequest
import app.tennyo.contract.wireName
import app.tennyo.host.engine.discovery.DiscoveryRoutes
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.session.ConnectionApproval
import app.tennyo.host.engine.session.HostRegistry
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.SessionCredential
import app.tennyo.host.engine.session.SessionMatch
import app.tennyo.host.engine.transport.ConnectionOrigin
import app.tennyo.host.engine.transport.HostHttpHeaderNames
import app.tennyo.host.engine.transport.HostHttpRequest
import app.tennyo.host.engine.transport.HostHttpResponse
import app.tennyo.host.engine.transport.HttpStatus
import app.tennyo.host.engine.transport.JSON_CONTENT_TYPE

private const val BEARER_PREFIX = "Bearer "

/**
 * The link's three public HTTP endpoints, for requests the listener already terminated TLS for. Everything after
 * pairing runs over the channel, which the listener upgrades and hands to [app.tennyo.host.engine.session.LinkChannel].
 */
class PublicEndpoints(
    private val discovery: DiscoveryRoutes? = null,
    private val sessions: (sessionId: String?, token: String?) -> SessionMatch? = HostRegistry::find,
) {
    suspend fun handle(request: HostHttpRequest): HostHttpResponse {
        if (!request.isSecure) {
            return error(HttpStatus.UPGRADE_REQUIRED, ErrorCodes.SECURE_TRANSPORT_REQUIRED, "The link requires HTTPS.")
        }
        val method = when (request.path) {
            LinkEndpoints.HELLO -> "GET"
            LinkEndpoints.PAIR, LinkEndpoints.DISCOVER -> "POST"
            LinkEndpoints.CHANNEL ->
                return error(
                    status = HttpStatus.BAD_REQUEST,
                    code = ErrorCodes.INVALID_REQUEST,
                    message = "The channel needs a WebSocket upgrade.",
                )

            else -> return HostHttpResponse(HttpStatus.NOT_FOUND)
        }
        if (request.method != method) {
            return error(
                status = HttpStatus.METHOD_NOT_ALLOWED,
                code = ErrorCodes.METHOD_NOT_ALLOWED,
                message = "This endpoint does not support the requested HTTP method.",
                headers = listOf(HostHttpHeaderNames.ALLOW to method),
            )
        }
        if (request.body.size > LinkEndpoints.requestBodyLimitBytes(request.path)) {
            return error(
                status = HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                code = ErrorCodes.REQUEST_TOO_LARGE,
                message = "The request body is too large.",
            )
        }
        return when (request.path) {
            LinkEndpoints.HELLO -> hello(request)
            LinkEndpoints.PAIR -> pair(request)
            else -> discover(request)
        }
    }

    private fun hello(request: HostHttpRequest): HostHttpResponse {
        // A pairing token buys the claim and confirm steps and nothing else, so `hello` reads as no session at all.
        val match = liveMatch(request.firstQueryParameter(LinkEndpoints.SESSION_PARAMETER), request.bearerToken())
            ?.takeIf { it.credential is SessionCredential.SessionToken }
            ?: return sessionUnavailable()
        return json(hostJson(match.session.toHandshakeResponse()))
    }

    private suspend fun pair(request: HostHttpRequest): HostHttpResponse {
        val signed = runCatching {
            LinkJson.decodeFromString(SignedPairRequest.serializer(), request.bodyText)
        }
            .getOrNull()
            ?: return invalidRequest()
        val pairRequest = runCatching {
            LinkJson.decodeFromString(PairRequest.serializer(), signed.request)
        }
            .getOrNull()
            ?: return invalidRequest()
        val match = liveMatch(signed.sessionId, request.bearerToken()) ?: return sessionUnavailable()
        val remote = request.origin == ConnectionOrigin.REMOTE
        val session = match.session
        session.stats.recordUploadBytes(request.body.size.toLong())
        val auth = session.auth
        val fault = remoteRefusal(remote, match, pairRequest) ?: when (pairRequest) {
            is PairRequest.Claim -> auth.verifyClaimProof(signed.request, signed.proof, match.credential.token)
            is PairRequest.Confirm, is PairRequest.ResumePair ->
                auth.verifyPendingProof(signed.request, signed.proof, match.credential.token)
        }
        if (fault != null) return refused(fault)
        return when (pairRequest) {
            is PairRequest.Claim ->
                if (remote) claimByApprovedDevice(session, pairRequest, signed) else claim(session, pairRequest, signed)

            is PairRequest.Confirm -> confirm(session, pairRequest, signed, match.credential)
            is PairRequest.ResumePair -> resumePair(session, pairRequest, signed)
        }
    }

    private suspend fun claim(
        session: HostSession,
        pair: PairRequest.Claim,
        signed: SignedPairRequest,
    ): HostHttpResponse {
        val claim = pair.claim
        if (claim.devicePublicKey != signed.proof.devicePublicKey ||
            claim.deviceFingerprint != signed.proof.deviceFingerprint
        ) {
            return refused(LinkFaultCode.WRONG_DEVICE)
        }
        val approval = session.auth.requestConnectionApproval(
            clientId = claim.clientId,
            clientName = claim.clientName,
            clientVersion = claim.clientVersion,
            devicePublicKey = claim.devicePublicKey,
            rememberedPairId = claim.rememberedPairId,
        )
        return when (approval) {
            ConnectionApproval.ACCEPTED -> {
                val requiresPairing = !session.auth.isDeviceApproved(claim.devicePublicKey)
                val challenge = if (requiresPairing) {
                    session.auth.currentPairingChallenge() ?: return pairingWordsExpired(session)
                } else {
                    null
                }
                json(hostJson(session.toSessionResponse(requiresPairing, challenge)), session)
            }

            ConnectionApproval.REJECTED ->
                error(HttpStatus.FORBIDDEN, ErrorCodes.CONNECTION_REJECTED, "The connection request was rejected.")

            ConnectionApproval.BUSY -> error(
                HttpStatus.CONFLICT,
                ErrorCodes.CONNECTION_REQUEST_PENDING,
                "Another mobile connection request is already pending.",
            )

            ConnectionApproval.TIMED_OUT -> error(
                status = HttpStatus.REQUEST_TIMEOUT,
                code = ErrorCodes.CONNECTION_REQUEST_TIMED_OUT,
                message = "The connection request timed out.",
            )
        }
    }

    /**
     * Away from the local network a phone only resumes what it already holds: no pairing token, no words, and no way
     * to ask the editor's user for anything. The fault that ends a request that may not go on, or `null`.
     */
    private fun remoteRefusal(remote: Boolean, match: SessionMatch, pairRequest: PairRequest): LinkFaultCode? = when {
        !remote -> null
        match.credential is SessionCredential.PairingToken -> LinkFaultCode.SESSION_NOT_FOUND
        pairRequest !is PairRequest.Claim -> LinkFaultCode.UNAUTHORIZED
        else -> null
    }

    /**
     * A remote claim is answered from what the session already knows. It never reaches
     * `requestConnectionApproval`, which would raise a prompt in the editor for a caller the user cannot see.
     */
    private fun claimByApprovedDevice(
        session: HostSession,
        pair: PairRequest.Claim,
        signed: SignedPairRequest,
    ): HostHttpResponse {
        val claim = pair.claim
        val provesItsDevice = claim.devicePublicKey == signed.proof.devicePublicKey &&
            claim.deviceFingerprint == signed.proof.deviceFingerprint
        if (!provesItsDevice || !session.auth.isDeviceApproved(claim.devicePublicKey)) {
            return refused(LinkFaultCode.WRONG_DEVICE)
        }
        return json(hostJson(session.toSessionResponse()), session)
    }

    private fun confirm(
        session: HostSession,
        pair: PairRequest.Confirm,
        signed: SignedPairRequest,
        credential: SessionCredential,
    ): HostHttpResponse {
        val confirmation = pair.confirmation
        if (confirmation.devicePublicKey != signed.proof.devicePublicKey ||
            confirmation.deviceFingerprint != signed.proof.deviceFingerprint
        ) {
            return refused(LinkFaultCode.WRONG_DEVICE)
        }
        val attempt = session.auth.confirmPairing(
            devicePublicKey = confirmation.devicePublicKey,
            deviceFingerprint = confirmation.deviceFingerprint,
            challengeId = confirmation.challengeId,
            attemptId = confirmation.attemptId,
            clientEphemeralPublicKey = confirmation.clientEphemeralPublicKey,
            clientPairingProof = confirmation.clientPairingProof,
            authenticatedToken = credential.token,
        )
        if (attempt.expired) return pairingWordsExpired(session)
        val response = PairingConfirmationResponse(
            status = attempt.status,
            session = if (attempt.status == PairingConfirmationStatus.Success) session.toSessionResponse() else null,
            pairingChallenge = attempt.pairingChallenge,
            encryptedPairMaterial = attempt.encryptedPairMaterial,
            encryptedArtifact = attempt.encryptedArtifact,
        )
        val reply = json(hostJson(response), session)
        if (attempt.status == PairingConfirmationStatus.StoppedServerExceededMaximumAttempts) {
            session.owner.stopSession(
                sessionId = session.artifact.sessionId,
                reason = "Pairing was stopped after too many incorrect attempts.",
            )
        }
        return reply
    }

    private fun resumePair(
        session: HostSession,
        pair: PairRequest.ResumePair,
        signed: SignedPairRequest,
    ): HostHttpResponse {
        val resume = pair.resume
        val response = session.auth.resumeRememberedPair(
            devicePublicKey = signed.proof.devicePublicKey,
            pairId = resume.pairId,
            challengeId = resume.challengeId,
            attemptId = resume.attemptId,
            clientEphemeralPublicKey = resume.clientEphemeralPublicKey,
            clientPairProof = resume.clientPairProof,
        )
        return json(hostJson(response), session)
    }

    private fun discover(request: HostHttpRequest): HostHttpResponse {
        val routes = discovery ?: return HostHttpResponse(HttpStatus.NOT_FOUND)
        val discoverRequest = runCatching {
            LinkJson.decodeFromString(DiscoverRequest.serializer(), request.bodyText)
        }
            .getOrNull()
            ?: return invalidRequest()
        return json(routes.handle(discoverRequest, request.remoteAddress, request.origin))
    }

    /** The session [sessionId] names with [token], and which credential that was; one past its deadline is ended. */
    private fun liveMatch(sessionId: String?, token: String?): SessionMatch? {
        val match = sessions(sessionId, token) ?: return null
        val reason = match.session.expirationReason() ?: return match
        match.session.owner.stopSession(match.session.artifact.sessionId, sessionExpirationMessage(reason), reason)
        return null
    }

    private fun pairingWordsExpired(session: HostSession): HostHttpResponse {
        session.owner.stopSession(session.artifact.sessionId, "Pairing words expired before confirmation.")
        return refused(LinkFaultCode.SESSION_EXPIRED)
    }

    private fun HostHttpRequest.bearerToken(): String? =
        headers[HostHttpHeaderNames.AUTHORIZATION]
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)

    private fun sessionUnavailable(): HostHttpResponse =
        refused(LinkFaultCode.SESSION_NOT_FOUND)

    private fun invalidRequest(): HostHttpResponse =
        error(HttpStatus.BAD_REQUEST, LinkFaultCode.INVALID_CALL.wireName, "The request could not be read.")

    /** A refusal a client acts on by its [fault] code. */
    private fun refused(fault: LinkFaultCode): HostHttpResponse {
        LOG.debug { "Refused a public link request: ${fault.wireName}" }
        val status = when (fault) {
            LinkFaultCode.PROTOCOL_VERSION_UNSUPPORTED -> HttpStatus.UPGRADE_REQUIRED
            LinkFaultCode.SESSION_EXPIRED -> HttpStatus.GONE
            else -> HttpStatus.UNAUTHORIZED
        }
        return error(status, fault.wireName, "The request was refused.")
    }

    private fun json(body: String, session: HostSession? = null): HostHttpResponse {
        val bytes = body.encodeToByteArray()
        session?.stats?.recordDownloadBytes(bytes.size.toLong())
        return HostHttpResponse(
            status = HttpStatus.OK,
            headers = listOf(HostHttpHeaderNames.CONTENT_TYPE to JSON_CONTENT_TYPE),
            body = bytes,
        )
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): HostHttpResponse =
        HostHttpResponse(
            status = status,
            headers = listOf(HostHttpHeaderNames.CONTENT_TYPE to JSON_CONTENT_TYPE) + headers,
            body = hostJson(ErrorResponse(error = code, message = message)).encodeToByteArray(),
        )

    private companion object {
        val LOG = HostLogger("PublicEndpoints")
    }
}
