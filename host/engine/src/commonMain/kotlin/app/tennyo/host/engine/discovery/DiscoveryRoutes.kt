package app.tennyo.host.engine.discovery

import app.tennyo.contract.DeviceProof
import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryConnectRequest
import app.tennyo.contract.DiscoveryConnectResponse
import app.tennyo.contract.DiscoveryLookup
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.OfferResponse
import app.tennyo.contract.PairingOfferStatus
import app.tennyo.contract.ProtocolVersions
import app.tennyo.host.engine.auth.NonceStore
import app.tennyo.host.engine.auth.deviceKeyHash
import app.tennyo.host.engine.auth.verifyDeviceProof
import app.tennyo.host.engine.pairing.DiscoveryAttempt
import app.tennyo.host.engine.pairing.RememberedPairMaterial
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.discoveryProofMatches
import app.tennyo.host.engine.pairing.isBoundToDevice
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.engine.pairing.protectPairMaterial
import app.tennyo.host.engine.pairing.protectSessionTicket
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.debugQuotedOrNone
import app.tennyo.host.engine.platform.debugShortId
import app.tennyo.host.engine.platform.discoveryDebug
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.rpc.hostJson
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.SessionTicket
import app.tennyo.host.engine.transport.ConnectionOrigin

/**
 * [LinkEndpoints.DISCOVER]: find a remembered IDE on the local network and connect to it without pairing again. Every
 * answer is a JSON body; a refusal is a status inside it, never an HTTP error, so probing learns nothing extra.
 */
class DiscoveryRoutes(private val directory: ProjectDirectory) {
    private val challenges = DiscoveryChallengeStore()
    private val proofNonces = NonceStore()

    /** The JSON answer to one [request] from [remoteAddress], whose connection came from [origin]. */
    fun handle(request: DiscoverRequest, remoteAddress: String?, origin: ConnectionOrigin): String {
        LOG.discoveryDebug { "request=${request::class.simpleName} remote=${remoteAddress.debugQuotedOrNone()}" }
        return when (request) {
            is DiscoverRequest.Discover -> discover(request.discover, remoteAddress)
            is DiscoverRequest.Connect -> connect(request.request, request.proof, remoteAddress)
            // Pairing is a local ceremony: a caller away from the network is never shown a window to pair through.
            DiscoverRequest.Offer -> if (origin == ConnectionOrigin.REMOTE) closedOffer() else offer()
        }
    }

    /**
     * Answers whether a pairing window is open here. Read from the window state in memory: it starts no session,
     * touches no pair store, and is not counted by the lockout — an offer is not an authentication attempt.
     */
    private fun offer(): String {
        val service = directory.pairingWindowOwner() ?: return closedOffer()
        if (service.discoveryBlockedMessage() != null) {
            return hostJson(OfferResponse(status = PairingOfferStatus.BLOCKED))
        }
        val window = service.host.pairingWindow.current() ?: return closedOffer()
        return hostJson(OfferResponse(status = PairingOfferStatus.OPEN, offer = window.toOffer()))
    }

    private fun closedOffer(): String = hostJson(OfferResponse(status = PairingOfferStatus.CLOSED))

    // Each check answers on its own: a discover that cannot go on says so and stops.
    @Suppress("ReturnCount")
    private fun discover(payload: DiscoveryLookup, remoteAddress: String?): String {
        LOG.discoveryDebug {
            "discover request companion=${payload.companionId.debugShortId()} " +
                "pair=${payload.pairId.debugShortId()} remote=${remoteAddress.debugQuotedOrNone()}"
        }
        val service = payload.companionId.takeIf { it.isNotBlank() }?.let(directory::discoveryOwner)
            ?: return unavailable(
                reason = "discover companion ${payload.companionId.debugShortId()} was not found",
                remoteAddress = remoteAddress,
            )
        if (!service.isDiscoveryEnabled()) {
            return unavailable("discovery is off for ${payload.companionId.debugShortId()}", remoteAddress)
        }
        if (!service.isDiscoveryAvailable()) {
            return unavailable(
                "a session is in use or another project answers for ${payload.companionId.debugShortId()}",
                remoteAddress,
            )
        }
        service.discoveryBlockedMessage()?.let { blockedMessage ->
            LOG.info("[discovery] Discover blocked for ${payload.companionId.debugShortId()}: $blockedMessage")
            return hostJson(
                DiscoverResponse(
                    status = DiscoveryStatus.BLOCKED,
                    blockedMessage = blockedMessage,
                )
            )
        }
        val record = service.host.findRememberedPair(payload.pairId)
            ?: return unavailable(
                reason = "discover remembered pair ${payload.pairId.debugShortId()} was not found",
                remoteAddress = remoteAddress,
            )
        if (record.companionId != payload.companionId) {
            return unavailable(
                reason = "discover remembered pair companion did not match",
                remoteAddress = remoteAddress,
            )
        }
        val projectChoices = service.discoveryProjectChoices()
        val issuedChallenge = challenges.issue(
            companionId = payload.companionId,
            pairId = payload.pairId,
            projectChoices = projectChoices,
        )
        LOG.info(
            "[discovery] Discover available: companion=${payload.companionId.debugShortId()} " +
                "pair=${payload.pairId.debugShortId()} projects=${projectChoices.size}",
        )
        LOG.discoveryDebug {
            "discover available companion=${payload.companionId.debugShortId()} " +
                "pair=${payload.pairId.debugShortId()} challenge=${issuedChallenge.challengeId.debugShortId()} " +
                "expiresAt=${issuedChallenge.expiresAtEpochMillis}"
        }
        return hostJson(
            DiscoverResponse(
                status = DiscoveryStatus.AVAILABLE,
                challenge = issuedChallenge,
                descriptor = service.discoveryDescriptor(),
                projectChoices = projectChoices,
            )
        )
    }

    /**
     * A connect runs in three steps: find the project and remembered pair it names, prove the device and the pair
     * against the challenge, and only then start or reuse a session and hand back an encrypted ticket.
     */
    @Suppress("ReturnCount")
    private fun connect(requestText: String, proof: DeviceProof, remoteAddress: String?): String {
        val payload = runCatching { HostJson.decodeFromString<DiscoveryConnectRequest>(requestText) }.getOrNull()
            ?: return connectUnavailable("connect payload could not be decoded", remoteAddress)
        LOG.discoveryDebug {
            "connect request companion=${payload.companionId.debugShortId()} pair=${payload.pairId.debugShortId()} " +
                "challenge=${payload.challengeId.debugShortId()} attempt=${payload.attemptId.debugShortId()} " +
                "remote=${remoteAddress.debugQuotedOrNone()}"
        }
        val target = target(payload, remoteAddress)
        if (target is ConnectStep.Answer) return target.json
        target as ConnectStep.Target
        val verified = verify(payload, proof, requestText, target, remoteAddress)
        if (verified is ConnectStep.Answer) return verified.json
        verified as ConnectStep.Verified
        return admit(payload, proof, target, verified, remoteAddress)
    }

    /** The project the connect names and the pair it claims, or the answer that ends it here. */
    @Suppress("ReturnCount")
    private fun target(payload: DiscoveryConnectRequest, remoteAddress: String?): ConnectStep {
        val service = payload.companionId.takeIf { it.isNotBlank() }?.let(directory::discoveryOwner)
            ?: return connectAnswer("companion ${payload.companionId.debugShortId()} was not found")
        if (!service.isDiscoveryEnabled()) {
            return connectAnswer(
                reason = "discovery is disabled companion=${payload.companionId.debugShortId()}",
                blockedMessage = directory.message(DiscoveryMessage.CONNECT_DISABLED),
            )
        }
        service.discoveryBlockedMessage()?.let { blockedMessage ->
            return connectAnswer(
                reason = "connect blocked companion=${payload.companionId.debugShortId()}",
                status = DiscoveryStatus.BLOCKED,
                blockedMessage = blockedMessage,
            )
        }
        val record = service.host.findRememberedPair(payload.pairId)
            ?: return connectAnswer(
                reason = "pair ${payload.pairId.debugShortId()} was revoked",
                status = DiscoveryStatus.PAIR_REVOKED,
            )
        if (payload.securityVersion != ProtocolVersions.SECURITY_VERSION) {
            return connectAnswer(
                reason = "the device speaks security version ${payload.securityVersion}",
                blockedMessage = directory.message(DiscoveryMessage.PAIRING_REQUIRED),
            )
        }
        if (record.companionId != payload.companionId) {
            return ConnectStep.Answer(
                countedFailure(service, remoteAddress, "the remembered pair companion did not match"),
            )
        }
        val challenge = challenges.find(payload.companionId, payload.pairId, payload.challengeId)
            ?: return ConnectStep.Answer(
                countedFailure(service, remoteAddress, "the challenge was missing or expired"),
            )
        val projectId = selectDiscoveryProjectId(payload.projectId, challenge.projectChoices)
        val project = projectId
            ?.let { directory.project(it) ?: service.takeIf { owner -> owner.projectId == it } }
            ?: return noProjectAnswer(payload, challenge.projectChoices.size)
        LOG.info(
            "[discovery] Connect request: companion=${payload.companionId.debugShortId()} " +
                "project=${payload.projectId.debugQuotedOrNone()}",
        )
        return ConnectStep.Target(service, project, record, challenge)
    }

    /** The device proof, the challenge attempt and the remembered pair's own proof, in that order. */
    private fun verify(
        payload: DiscoveryConnectRequest,
        proof: DeviceProof,
        requestText: String,
        target: ConnectStep.Target,
        remoteAddress: String?,
    ): ConnectStep {
        val endpoints = target.project.discoveryEndpoints()
        val deviceKeyHash = deviceKeyHash(proof.devicePublicKey)
        val failure = { reason: String -> ConnectStep.Answer(countedFailure(target.service, remoteAddress, reason)) }
        val ticket = connectTicket(payload, target, endpoints)
        if (!deviceIdentityMatchesKnownPair(proof, target.record, ticket, requestText)) {
            return failure("the device proof was invalid")
        }
        if (!challenges.consumeAttempt(target.challenge.challengeId, payload.attemptId)) {
            return failure("the connect attempt was replayed")
        }
        if (!target.record.isBoundToDevice(deviceKeyHash)) {
            return failure("the remembered device key did not match")
        }
        val attempt = DiscoveryAttempt(
            companionId = payload.companionId,
            fingerprint = endpoints.transportFingerprint,
            route = LinkEndpoints.DISCOVER,
            challengeId = payload.challengeId,
            attemptId = payload.attemptId,
            serverChallenge = target.challenge.serverChallenge,
            deviceFingerprint = deviceKeyHash,
            clientEphemeralPublicKey = payload.clientEphemeralPublicKey,
            companionEphemeralPublicKey = target.challenge.ephemeralKeyPair.publicKeyBase64,
        )
        if (!discoveryProofMatches(target.record, attempt, payload.clientPairProof)) {
            return failure("the remembered pair proof was invalid")
        }
        // pairSecret authenticated the exchange; the ticket travels under the ECDH key instead, so a leaked pairSecret
        // cannot unwrap a recorded connect after the fact.
        val responseKey = target.challenge.ephemeralKeyPair.pairMaterialKey(
            clientEphemeralPublicKey = payload.clientEphemeralPublicKey,
            serverChallenge = target.challenge.serverChallenge,
            transcriptHash = attempt.transcriptHash(),
        ) ?: return failure("the key exchange could not be completed")
        return ConnectStep.Verified(deviceKeyHash, responseKey)
    }

    /** Starts or reuses the project's session for this device and answers with its encrypted ticket. */
    private fun admit(
        payload: DiscoveryConnectRequest,
        proof: DeviceProof,
        target: ConnectStep.Target,
        verified: ConnectStep.Verified,
        remoteAddress: String?,
    ): String {
        val session = sessionFor(target.project, verified.deviceKeyHash)
            ?: return connectAnswer(
                reason = "no session is available companion=${payload.companionId.debugShortId()}",
                blockedMessage = directory.message(DiscoveryMessage.SESSION_UNAVAILABLE),
            ).json
        session.auth.approveDevice(
            devicePublicKey = proof.devicePublicKey,
            deviceLabel = target.record.deviceLabel,
            requestId = "discovery-${hostRandomUuid()}",
        )
        val rotatedPair = rotatePairForDiscovery(target.project, target.record, verified.deviceKeyHash)
        LOG.info(
            "[discovery] Connect succeeded: companion=${payload.companionId.debugShortId()} " +
                "session=${session.artifact.sessionId.debugShortId()} " +
                "project=${target.project.host.surfaces.projectInfo.name}",
        )
        LOG.discoveryDebug {
            "connect succeeded companion=${payload.companionId.debugShortId()} " +
                "device=${target.record.deviceLabel.debugQuotedOrNone()} rotatedPair=${rotatedPair != null} " +
                "remote=${remoteAddress.debugQuotedOrNone()}"
        }
        return hostJson(
            DiscoveryConnectResponse(
                status = DiscoveryStatus.AVAILABLE,
                encryptedArtifact = protectSessionTicket(session.currentTicket(), verified.responseKey),
                encryptedRotatedPair = rotatedPair?.let { (material, _) ->
                    protectPairMaterial(material, verified.responseKey)
                },
            )
        )
    }

    /** The project's active session when this device may use it, or a fresh one; `null` when none can be started. */
    private fun sessionFor(project: DiscoveryProject, deviceKeyHash: String): HostSession? {
        val reusable = project.host.activeSession()?.takeIf {
            it.auth.approvedDeviceKeyHash() == null || it.auth.approvedDeviceKeyHash() == deviceKeyHash
        }
        if (reusable != null) return reusable
        if (project.host.activeSession() != null) return null
        project.host.startSession()
        return project.host.activeSession()?.takeIf {
            it.auth.approvedDeviceKeyHash() == null || it.auth.approvedDeviceKeyHash() == deviceKeyHash
        }
    }

    /** The session a connect's device proof is verified against: the challenge stands in for a session token. */
    private fun connectTicket(
        payload: DiscoveryConnectRequest,
        target: ConnectStep.Target,
        endpoints: HostEndpoints,
    ): SessionTicket =
        SessionTicket(
            endpoint = endpoints.primary,
            sessionId = payload.companionId,
            token = target.challenge.connectToken,
            fingerprint = endpoints.transportFingerprint,
            projectName = target.project.discoveryDescriptor().projectName,
            projectPath = target.project.host.surfaces.projectInfo.displayPath,
            companionId = payload.companionId,
            hostVersion = target.project.host.surfaces.projectInfo.hostVersion,
            capabilities = emptySet(),
        )

    private fun noProjectAnswer(payload: DiscoveryConnectRequest, choiceCount: Int): ConnectStep.Answer {
        LOG.warn(
            "[discovery] Connect failed: no project service found for " +
                "companion=${payload.companionId.debugShortId()} " +
                "requestedProject=${payload.projectId.debugQuotedOrNone()} choices=$choiceCount",
        )
        return connectAnswer(
            reason = "no project was selected companion=${payload.companionId.debugShortId()}",
            blockedMessage = directory.message(DiscoveryMessage.PROJECT_NOT_FOUND),
        )
    }

    private fun connectAnswer(
        reason: String,
        status: DiscoveryStatus = DiscoveryStatus.UNAVAILABLE,
        blockedMessage: String? = null,
    ): ConnectStep.Answer {
        LOG.info("[discovery] Connect $status: $reason")
        return ConnectStep.Answer(hostJson(DiscoveryConnectResponse(status = status, blockedMessage = blockedMessage)))
    }

    private fun connectUnavailable(reason: String, remoteAddress: String?): String {
        LOG.discoveryDebug {
            "connect unavailable reason=${reason.debugQuotedOrNone()} remote=${remoteAddress.debugQuotedOrNone()}"
        }
        return hostJson(DiscoveryConnectResponse(status = DiscoveryStatus.UNAVAILABLE))
    }

    /** One step of a connect: the answer to send, the project it targets, or what it proved. */
    private sealed interface ConnectStep {
        data class Answer(val json: String) : ConnectStep

        data class Target(
            val service: DiscoveryProject,
            val project: DiscoveryProject,
            val record: RememberedPairRecord,
            val challenge: DiscoveryChallengeStore.PendingDiscoveryChallenge,
        ) : ConnectStep

        data class Verified(val deviceKeyHash: String, val responseKey: ByteArray) : ConnectStep
    }

    /** The proof must verify fresh and once, from the device the remembered pair is bound to. */
    private fun deviceIdentityMatchesKnownPair(
        proof: DeviceProof,
        record: RememberedPairRecord,
        artifact: SessionTicket,
        requestText: String,
    ): Boolean =
        proofNonces.verifyDeviceProof(
            ticket = artifact,
            endpoint = LinkEndpoints.DISCOVER,
            requestText = requestText,
            proof = proof,
            nowEpochMillis = hostWallClockMillis(),
        ) == null && record.isBoundToDevice(proof.deviceFingerprint)

    private fun rotatePairForDiscovery(
        service: DiscoveryProject,
        record: RememberedPairRecord,
        deviceKeyHash: String,
    ): Pair<RememberedPairMaterial, RememberedPairRecord>? {
        val (material, replacement) = newRememberedPair(
            deviceLabel = record.deviceLabel,
            deviceKeyHash = deviceKeyHash,
        )
        val companionId = service.host.platform.companionId()
        val enrichedMaterial = material.copy(companionId = companionId)
        val enrichedRecord = replacement.copy(
            companionId = companionId,
            lastProjectName = service.discoveryDescriptor().projectName,
            lastHostVersion = service.host.surfaces.projectInfo.hostVersion,
            lastUsedAtEpochMillis = hostWallClockMillis(),
            lastKnownNetworkLabel = service.discoveryDescriptor().lastKnownNetworkLabel,
        )
        return if (
            service.host.replaceRememberedPair(
                pairId = record.pairId,
                expectedPairSecret = record.pairSecret,
                replacement = enrichedRecord,
            )
        ) {
            LOG.discoveryDebug {
                "rotated remembered pair pair=${record.pairId.debugShortId()} " +
                    "companion=${companionId.debugShortId()} deviceKey=${deviceKeyHash.debugShortId()}"
            }
            enrichedMaterial to enrichedRecord
        } else {
            LOG.discoveryDebug {
                "failed to rotate remembered pair pair=${record.pairId.debugShortId()} " +
                    "companion=${companionId.debugShortId()}"
            }
            null
        }
    }

    private fun countedFailure(
        service: DiscoveryProject,
        remoteAddress: String?,
        reason: String,
    ): String {
        val message = "Discovery verification failed because $reason."
        val blocked = service.recordDiscoverySecurityFailure(message, remoteAddress)
        LOG.discoveryDebug {
            "counted security failure blocked=$blocked remote=${remoteAddress.debugQuotedOrNone()} " +
                "message=${message.debugQuotedOrNone()}"
        }
        return hostJson(
            DiscoveryConnectResponse(
                status = if (blocked) DiscoveryStatus.BLOCKED else DiscoveryStatus.UNAVAILABLE,
                blockedMessage = service.discoveryBlockedMessage(),
            )
        )
    }

    private companion object {
        val LOG = HostLogger("DiscoveryRoutes")
    }

    private fun unavailable(
        reason: String,
        remoteAddress: String?,
    ): String {
        // At info, like "available": a phone that never sees its known host is diagnosed from this line alone.
        LOG.info("[discovery] Discover unavailable: $reason (remote ${remoteAddress.debugQuotedOrNone()})")
        return hostJson(
            DiscoverResponse(
                status = DiscoveryStatus.UNAVAILABLE,
            )
        )
    }
}
