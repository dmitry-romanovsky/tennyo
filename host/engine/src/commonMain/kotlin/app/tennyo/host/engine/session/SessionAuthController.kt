package app.tennyo.host.engine.session


import app.tennyo.contract.DeviceProof
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.RememberedPairResumeResponse
import app.tennyo.contract.RememberedPairResumeStatus
import app.tennyo.contract.SessionEndReason
import app.tennyo.contract.SessionTimeouts
import app.tennyo.crypto.sha256Url
import app.tennyo.host.engine.auth.NonceStore
import app.tennyo.host.engine.auth.deviceKeyHash
import app.tennyo.host.engine.auth.verifyDeviceProof
import app.tennyo.host.engine.pairing.PairingConfirmationAttempt
import app.tennyo.host.engine.pairing.PairingConfirmationState
import app.tennyo.host.engine.pairing.PairingVerifier
import app.tennyo.host.engine.pairing.RememberedPairAttempt
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.isBoundToDevice
import app.tennyo.host.engine.pairing.newEphemeralKeyPair
import app.tennyo.host.engine.pairing.newPairingChallenge
import app.tennyo.host.engine.pairing.newPairingSecret
import app.tennyo.host.engine.pairing.protectSessionTicket
import app.tennyo.host.engine.pairing.rememberedPairProofMatches
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.debugShortId
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.rpc.toSessionResponse
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val CONNECTION_APPROVAL_TIMEOUT_SECONDS = 120L
private const val DEFAULT_CLIENT_NAME = "Mobile device"
private const val MAX_CLIENT_LABEL_CHARS = 64
private val UNPRINTED_CATEGORIES =
    setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

/**
 * What an unauthenticated claim says about itself, made safe to show in the approval prompt and the log: one short
 * line, so it cannot forge log lines, reverse text or push the real prompt out of view.
 */
private fun clientLabel(claimed: String, fallback: String): String =
    claimed.filterNot { it.isISOControl() || it.category in UNPRINTED_CATEGORIES }
        .take(MAX_CLIENT_LABEL_CHARS)
        .ifBlank { fallback }

private fun normalizeClientId(clientId: String): String = clientLabel(clientId, "android")

private fun normalizeClientName(clientName: String): String = clientLabel(clientName, DEFAULT_CLIENT_NAME)

private fun rememberedPairRejected(pairingChallenge: PairingChallenge?): RememberedPairResumeResponse =
    RememberedPairResumeResponse(
        status = RememberedPairResumeStatus.Rejected,
        pairingChallenge = pairingChallenge,
    )

/** The user accepted the request, or it waits only for the pairing words. */
private val MutableConnectionRequest.isAccepted: Boolean
    get() = status == ConnectionRequestStatus.ACCEPTED || status == ConnectionRequestStatus.AWAITING_PAIRING

private fun ConnectionRequestStatus.isVisibleConnectionRequestStatus(): Boolean =
    this == ConnectionRequestStatus.PENDING ||
            this == ConnectionRequestStatus.ACCEPTED ||
            this == ConnectionRequestStatus.AWAITING_PAIRING

class SessionAuthController(
    private val session: HostSession,
) {
    private val connectionRequestLock = HostMonitor()
    private val connectionRequests = HostConcurrentMap<String, MutableConnectionRequest>()

    // The pairing secret, its grid, and the ephemeral key pair are drawn once per session and repeated on every
    // rotated challenge: the words never change, so re-rolling would only force the user to search the grid again.
    private val pairingVerifier = PairingVerifier(
        secret = newPairingSecret(),
        ephemeralKeyPair = newEphemeralKeyPair(),
    )
    private val pairingConfirmationState = PairingConfirmationState(
        verifier = pairingVerifier,
        initialChallenge = newPairingChallenge(
            verifier = pairingVerifier,
            nowEpochMillis = session.startedAtEpochMillis,
            expiresAtEpochMillis = session.startedAtEpochMillis + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS,
        ),
        expiresAtEpochMillis = session.startedAtEpochMillis + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS,
    )
    private val approvedDeviceKeyHash = AtomicReference<String?>(null)
    private val approvedDeviceName = AtomicReference<String?>(null)
    private val approvedDeviceLastSeenAt = AtomicLong(0L)
    private val acceptedProofNonces = NonceStore()
    private val rememberedPairs = RememberedPairIssuer(session)

    private val artifact get() = session.artifact
    private val owner get() = session.owner

    fun hasApprovedDeviceTimedOut(nowEpochMillis: Long): Boolean {
        if (approvedDeviceKeyHash.load() == null) return false
        val lastSeenAt = approvedDeviceLastSeenAt.load()
        return lastSeenAt > 0L &&
                nowEpochMillis - lastSeenAt > SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS
    }

    fun nonAbsoluteExpirationReason(nowEpochMillis: Long): SessionEndReason? {
        val approvalDeadline = session.startedAtEpochMillis + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS
        return when {
            approvedDeviceKeyHash.load() == null && nowEpochMillis >= approvalDeadline ->
                SessionEndReason.PendingExpired

            hasApprovedDeviceTimedOut(nowEpochMillis) -> SessionEndReason.IdleExpired
            else -> null
        }
    }

    fun hasRecentlySeenApprovedDevice(nowEpochMillis: Long): Boolean {
        if (approvedDeviceKeyHash.load() == null) return false
        val lastSeenAt = approvedDeviceLastSeenAt.load()
        return lastSeenAt > 0L &&
                nowEpochMillis - lastSeenAt <= SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS
    }

    fun approvedDeviceName(): String? = approvedDeviceName.load()

    fun approvedDeviceKeyHash(): String? = approvedDeviceKeyHash.load()

    fun isDeviceApproved(devicePublicKey: String): Boolean =
        approvedDeviceKeyHash.load() == deviceKeyHash(devicePublicKey)

    /**
     * Asks the user to let a device in and suspends until they answer, the wait times out, or the request is
     * settled without them: an already trusted device, or a device resuming a remembered pair while discovery is on.
     */
    suspend fun requestConnectionApproval(
        clientId: String,
        clientName: String,
        clientVersion: String,
        devicePublicKey: String,
        rememberedPairId: String?,
    ): ConnectionApproval {
        val canResumeRememberedPair = rememberedPairId
            ?.takeIf { it.isNotBlank() }
            ?.let { owner.findRememberedPair(it) != null }
            ?: false
        val queued = queueConnectionRequest(
            clientId = clientId,
            clientName = clientName,
            clientVersion = clientVersion,
            devicePublicKey = devicePublicKey,
            canResumeRememberedPair = canResumeRememberedPair,
        )
        val request = when (queued) {
            is QueuedRequest.Decided -> return queued.approval
            is QueuedRequest.Waiting -> queued.request
        }
        if (request.status == ConnectionRequestStatus.ACCEPTED) request.decided.complete(Unit)
        owner.platform.onSessionStateChanged()
        if (request.status == ConnectionRequestStatus.PENDING) {
            owner.platform.showConnectionRequest(artifact.sessionId, request.toPublicRequest())
        }
        LOG.info("Connection request ${request.id} from ${request.clientName} ${request.clientVersion}")
        val decided = withTimeoutOrNull(CONNECTION_APPROVAL_TIMEOUT_SECONDS.seconds) {
            request.decided.await()
        } != null
        return settleConnectionRequest(request, decided)
    }

    /** Places the request in the queue, unless the session can answer it right away. */
    private fun queueConnectionRequest(
        clientId: String,
        clientName: String,
        clientVersion: String,
        devicePublicKey: String,
        canResumeRememberedPair: Boolean,
    ): QueuedRequest = connectionRequestLock.guarded {
        val deviceKeyHash = deviceKeyHash(devicePublicKey)
        val normalizedClientId = normalizeClientId(clientId)
        val approvedHash = approvedDeviceKeyHash.load()
        val pendingElsewhere = connectionRequests.values()
            .firstOrNull { it.status.isVisibleConnectionRequestStatus() }
            ?.takeIf { it.deviceKeyHash != deviceKeyHash }
        val newRequest = { status: ConnectionRequestStatus ->
            MutableConnectionRequest(
                id = normalizedClientId,
                clientName = normalizeClientName(clientName),
                clientVersion = clientLabel(clientVersion, "unknown"),
                devicePublicKey = devicePublicKey,
                deviceKeyHash = deviceKeyHash,
                pairingWords = pairingVerifier.words,
                canResumeRememberedPair = canResumeRememberedPair,
                requestedAtEpochMillis = session.monotonicNow(),
                status = status,
            )
        }
        when {
            approvedHash != null && approvedHash != deviceKeyHash -> {
                LOG.info(
                    "Rejected connection request from device ${deviceKeyHash.debugShortId()} " +
                        "because another device is already approved",
                )
                QueuedRequest.Decided(ConnectionApproval.REJECTED)
            }

            approvedHash == deviceKeyHash && owner.platform.isDiscoveryEnabled() -> {
                LOG.info("Accepted connection request $normalizedClientId from the already trusted device")
                QueuedRequest.Decided(ConnectionApproval.ACCEPTED)
            }

            canResumeRememberedPair && owner.platform.isDiscoveryEnabled() -> {
                connectionRequests.clear()
                val request = newRequest(ConnectionRequestStatus.ACCEPTED)
                connectionRequests[deviceKeyHash] = request
                QueuedRequest.Waiting(request)
            }

            pendingElsewhere != null -> {
                LOG.info(
                    "Rejected connection request $normalizedClientId because " +
                        "${pendingElsewhere.id} is already pending",
                )
                QueuedRequest.Decided(ConnectionApproval.BUSY)
            }

            else -> {
                val existing = connectionRequests[deviceKeyHash]
                    ?.takeIf { it.status != ConnectionRequestStatus.REJECTED }
                QueuedRequest.Waiting(
                    existing ?: newRequest(ConnectionRequestStatus.PENDING).also {
                        connectionRequests[deviceKeyHash] = it
                    },
                )
            }
        }
    }

    /** Turns the settled request into the answer the device receives, and clears the request from the IDE. */
    private fun settleConnectionRequest(request: MutableConnectionRequest, decided: Boolean): ConnectionApproval =
        when {
            !decided -> {
                connectionRequests.remove(request.deviceKeyHash, request)
                owner.platform.expireConnectionRequest(artifact.sessionId, request.id)
                LOG.info("Connection request ${request.id} timed out")
                ConnectionApproval.TIMED_OUT
            }

            request.status == ConnectionRequestStatus.ACCEPTED ||
                    request.status == ConnectionRequestStatus.AWAITING_PAIRING -> {
                owner.platform.expireConnectionRequests(artifact.sessionId)
                LOG.info("Connection request ${request.id} accepted; waiting for device key confirmation")
                ConnectionApproval.ACCEPTED
            }

            else -> {
                owner.platform.expireConnectionRequest(artifact.sessionId, request.id)
                LOG.info("Connection request ${request.id} rejected")
                ConnectionApproval.REJECTED
            }
        }

    /** A connection request that is waiting for the user, or the answer the session already has for it. */
    private sealed interface QueuedRequest {
        data class Decided(val approval: ConnectionApproval) : QueuedRequest

        data class Waiting(val request: MutableConnectionRequest) : QueuedRequest
    }

    fun pendingConnectionRequests(): List<ConnectionRequest> =
        connectionRequests.values()
            .filter { it.status.isVisibleConnectionRequestStatus() }
            .sortedBy { it.requestedAtEpochMillis }
            .take(1)
            .map { it.toPublicRequest() }

    fun acceptConnectionRequest(requestId: String): Boolean {
        val request = connectionRequests.values().firstOrNull { it.id == requestId } ?: return false
        val approvedHash = approvedDeviceKeyHash.load()
        if (approvedHash != null && request.deviceKeyHash != approvedHash) return false
        request.status = if (request.canResumeRememberedPair) {
            ConnectionRequestStatus.ACCEPTED
        } else {
            ConnectionRequestStatus.AWAITING_PAIRING
        }
        connectionRequests.entries()
            .filter { (_, candidate) -> candidate.deviceKeyHash != request.deviceKeyHash }
            .forEach { (key, candidate) -> connectionRequests.remove(key, candidate) }
        request.decided.complete(Unit)
        owner.platform.expireConnectionRequests(artifact.sessionId)
        owner.platform.onSessionStateChanged()
        LOG.info("Accepted connection request $requestId; waiting for device key confirmation")
        return true
    }

    fun rejectConnectionRequest(requestId: String): Boolean {
        val request = connectionRequests.values().firstOrNull { it.id == requestId } ?: return false
        request.status = ConnectionRequestStatus.REJECTED
        request.decided.complete(Unit)
        connectionRequests.remove(request.deviceKeyHash, request)
        owner.platform.expireConnectionRequest(artifact.sessionId, requestId)
        owner.platform.onSessionStateChanged()
        LOG.info("Rejected connection request $requestId")
        return true
    }

    /** Approves the device whose connection request the user accepted; `null` when there is no such request. */
    fun confirmDevice(devicePublicKey: String): String? {
        val request = connectionRequests[deviceKeyHash(devicePublicKey)]?.takeIf { it.isAccepted } ?: return null
        approveDevice(devicePublicKey, request.clientName, request.id)
        return request.clientName
    }

    fun approveDevice(
        devicePublicKey: String,
        deviceLabel: String,
        requestId: String,
    ) {
        val deviceKeyHash = deviceKeyHash(devicePublicKey)
        approvedDeviceKeyHash.store(deviceKeyHash)
        approvedDeviceName.store(deviceLabel)
        approvedDeviceLastSeenAt.store(session.monotonicNow())
        connectionRequests.clear()
        owner.platform.expireConnectionRequests(artifact.sessionId)
        owner.platform.onSessionStateChanged()
        LOG.info(
            "Approved device ${deviceKeyHash.debugShortId()} for request $requestId " +
                "on companion ${artifact.companionId.debugShortId()}",
        )
    }

    /** Pairing attempts this session has left, as the verifier counts them. */
    val pairingAttemptsLeft: Int get() = pairingConfirmationState.attemptsLeft()

    fun currentPairingChallenge(): PairingChallenge? =
        pairingConfirmationState.currentChallenge()

    fun confirmPairing(
        devicePublicKey: String,
        deviceFingerprint: String,
        challengeId: String,
        attemptId: String,
        clientEphemeralPublicKey: String,
        clientPairingProof: String,
        /**
         * The token that authenticated the confirmation. A device pairing by name never learns the session token,
         * so its transcript binds the window's pairing token instead; the layout is unchanged either way.
         */
        authenticatedToken: String = artifact.token,
    ): PairingConfirmationAttempt {
        val attempt = pairingConfirmationState.evaluate(
            artifact = artifact,
            sessionTokenHash = sha256Url(authenticatedToken),
            route = LinkEndpoints.PAIR,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            challengeId = challengeId,
            attemptId = attemptId,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            clientPairingProof = clientPairingProof,
        )
        if (attempt.status != PairingConfirmationStatus.Success) return attempt
        // The verifier reports success only with the exchange's key, so a missing key is the missing-request case.
        val responseKey = attempt.pairMaterialKey
        val deviceLabel = responseKey?.let { confirmDevice(devicePublicKey) }
            ?: return PairingConfirmationAttempt(
                status = PairingConfirmationStatus.IncorrectSelection,
                pairingChallenge = currentPairingChallenge(),
            )
        // The user just approved this device, so the window has done its job: its token stops working here, and the
        // session token reaches the phone only sealed under the key this exchange agreed.
        owner.pairingWindow.close()
        return attempt.copy(
            encryptedPairMaterial = rememberedPairs.issue(
                deviceLabel = deviceLabel,
                deviceKeyHash = deviceKeyHash(devicePublicKey),
                responseKey = responseKey,
            ),
            encryptedArtifact = protectSessionTicket(session.currentTicket(), responseKey),
        )
    }

    private fun rejected(): RememberedPairResumeResponse = rememberedPairRejected(currentPairingChallenge())

    fun resumeRememberedPair(
        devicePublicKey: String,
        pairId: String,
        challengeId: String,
        attemptId: String,
        clientEphemeralPublicKey: String,
        clientPairProof: String,
    ): RememberedPairResumeResponse {
        val record = owner.findRememberedPair(pairId)
            ?: return rejected()
        val deviceFingerprint = deviceKeyHash(devicePublicKey)
        if (!record.isBoundToDevice(deviceFingerprint)) {
            return rejected()
        }
        val challenge = pairingConfirmationState.consumePairResumeChallenge(
            challengeId = challengeId,
            attemptId = attemptId,
        ) ?: return rejected()
        val attempt = RememberedPairAttempt(
            artifact = artifact,
            route = LinkEndpoints.PAIR,
            challengeId = challengeId,
            attemptId = attemptId,
            serverChallenge = challenge.serverChallenge,
            deviceFingerprint = deviceFingerprint,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            companionEphemeralPublicKey = pairingVerifier.ephemeralKeyPair.publicKeyBase64,
        )
        if (!rememberedPairProofMatches(record, attempt, clientPairProof)) {
            return rejected()
        }
        val request = connectionRequests[deviceFingerprint]?.takeIf { it.isAccepted } ?: return rejected()
        // pairSecret authenticated the exchange above; the rotated material travels under the ECDH key so a leaked
        // pairSecret cannot unwrap a recorded rotation later.
        val responseKey = pairingVerifier.ephemeralKeyPair.pairMaterialKey(
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            serverChallenge = challenge.serverChallenge,
            transcriptHash = attempt.transcriptHash(),
        ) ?: return rejected()
        val encryptedRotatedPair = rememberedPairs.rotate(
            previousRecord = record,
            deviceLabel = request.clientName,
            responseKey = responseKey,
        ) ?: return rejected()
        // Approve only once nothing can fail, so a refused resume never leaves the device connected.
        approveDevice(devicePublicKey, request.clientName, request.id)
        return RememberedPairResumeResponse(
            status = RememberedPairResumeStatus.Success,
            session = session.toSessionResponse(),
            encryptedRotatedPair = encryptedRotatedPair,
        )
    }

    /** A pairing claim: any device may ask, so only the proof itself is checked. */
    fun verifyClaimProof(requestText: String, proof: DeviceProof, authenticatedToken: String): LinkFaultCode? =
        verifyProof(LinkEndpoints.PAIR, requestText, proof, authenticatedToken)

    /** A pairing confirmation or remembered-pair resume, which only the device the user accepted may send. */
    fun verifyPendingProof(requestText: String, proof: DeviceProof, authenticatedToken: String): LinkFaultCode? {
        val request = connectionRequests[proof.deviceFingerprint]
        if (request?.status != ConnectionRequestStatus.ACCEPTED &&
            request?.status != ConnectionRequestStatus.AWAITING_PAIRING
        ) {
            return LinkFaultCode.WRONG_DEVICE
        }
        return verifyProof(LinkEndpoints.PAIR, requestText, proof, authenticatedToken)
    }

    /** A channel open, which only the approved device may send. */
    fun verifyApprovedProof(endpoint: String, requestText: String, proof: DeviceProof): LinkFaultCode? {
        if (approvedDeviceKeyHash.load() != proof.deviceFingerprint) return LinkFaultCode.WRONG_DEVICE
        val fault = verifyProof(endpoint, requestText, proof)
        if (fault == null) markApprovedDeviceSeen()
        return fault
    }

    private fun verifyProof(
        endpoint: String,
        requestText: String,
        proof: DeviceProof,
        authenticatedToken: String = artifact.token,
    ): LinkFaultCode? =
        acceptedProofNonces.verifyDeviceProof(
            ticket = artifact,
            endpoint = endpoint,
            requestText = requestText,
            proof = proof,
            nowEpochMillis = session.monotonicNow(),
            sessionTokenHash = sha256Url(authenticatedToken),
        )

    /** The approved device just proved itself, by a proof or a verified call; the idle timeout starts over. */
    fun markApprovedDeviceSeen() {
        if (approvedDeviceKeyHash.load() != null) approvedDeviceLastSeenAt.store(session.monotonicNow())
    }

    fun close() {
        connectionRequests.values().forEach { request -> request.decided.complete(Unit) }
        connectionRequests.clear()
    }

    private companion object {
        private val LOG = HostLogger("SessionAuthController")
    }
}
