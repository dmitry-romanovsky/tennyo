package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.Discovery
import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryConnectRequest
import app.tennyo.contract.DiscoveryConnectResponse
import app.tennyo.contract.DiscoveryLookup
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkJson
import app.tennyo.contract.ProtocolVersions
import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.debugShortIdOrNone
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.discoveryInfo
import app.tennyo.mobile.link.discoveryWarn
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.transport.LinkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reconnecting to a known IDE without a ticket: find it on the local network over DNS-SD, ask whether it will take this
 * device, and prove the remembered pair to receive a fresh ticket.
 */
class LocalDiscovery(
    private val http: LinkHttp,
    private val deviceIdentity: DeviceIdentity,
    private val rememberedPairs: RememberedPairs,
    private val knownHosts: KnownHosts,
    private val gateway: LocalNetworkDiscoveryGateway,
) : KnownHostDiscovery {

    override fun knownHostTargets(): List<KnownHostTarget> = knownHosts.list()

    override suspend fun forgetKnownHostTarget(companionId: String) {
        discoveryDebug { "forget known target companion=${companionId.debugShortId()}" }
        rememberedPairs.forget(companionId)
        knownHosts.remove(companionId)
    }

    override suspend fun discoverCandidates(
        targets: List<KnownHostTarget>,
        services: List<LocalNetworkServiceAdvertisement>,
        dialStored: Boolean,
    ): Map<String, KnownHostAnswer?> {
        if (targets.isEmpty()) return emptyMap()
        discoveryDebug {
            "local discovery over the caller's browse requested=${targets.size} services=${services.size} " +
                "ids=${services.joinToString { it.serviceInstanceId.debugShortId() }}"
        }
        // One target that cannot be reached must not take the rest of the list with it.
        return targets.associate { target ->
            target.companionId to runCatchingCancellable { discoverCandidate(target, services, dialStored) }.getOrNull()
        }
    }

    override suspend fun discoverCandidate(target: KnownHostTarget): KnownHostAnswer? {
        discoveryDebug {
            "single-target local discovery browse requested companion=${target.companionId.debugShortId()} " +
                "serviceType=${Discovery.SERVICE_TYPE.debugQuotedOrNone()}"
        }
        return discoverCandidate(target, browse(), dialStored = true)
    }

    override suspend fun addAddress(companionId: String, endpoint: String) =
        knownHosts.addEndpoint(companionId, endpoint)

    override suspend fun removeAddress(companionId: String, endpoint: String) =
        knownHosts.removeEndpoint(companionId, endpoint)

    override suspend fun checkAddress(target: KnownHostTarget, endpoint: String): AddressCheck {
        val pair = rememberedPair(target) ?: return AddressCheck.REFUSED
        return when (probeAt(target, pair, endpoint)?.status) {
            null -> AddressCheck.NO_ANSWER
            DiscoveryStatus.AVAILABLE, DiscoveryStatus.BLOCKED -> AddressCheck.REACHED
            DiscoveryStatus.UNAVAILABLE, DiscoveryStatus.PAIR_REVOKED -> AddressCheck.REFUSED
        }
    }

    private suspend fun browse(): List<LocalNetworkServiceAdvertisement> =
        gateway.discoverServices(serviceType = Discovery.SERVICE_TYPE, timeoutMillis = DISCOVERY_TIMEOUT_MILLIS)

    /**
     * Probes every service advertising [target]; the first that is not unavailable answers. A service that fails or
     * does not answer in time is skipped, since a host is often advertised on an address this phone cannot reach.
     * When DNS-SD gave no answer and [dialStored] allows it, the host's stored addresses are dialed instead.
     */
    private suspend fun discoverCandidate(
        target: KnownHostTarget,
        services: List<LocalNetworkServiceAdvertisement>,
        dialStored: Boolean,
    ): KnownHostAnswer? {
        val pair = rememberedPair(target) ?: return null.also {
            discoveryInfo("[discovery] ${target.companionId.debugShortId()} has no remembered pair; pair again")
        }
        val matching = services
            .filter { it.advertises(target.companionId) }
            .map { it.discoveryEndpoint() }
            .distinct()
        if (matching.isEmpty()) {
            discoveryDebug { "discover probe skipped: no service for companion=${target.companionId.debugShortId()}" }
        }
        val nearby = probeInOrder(target, pair, matching)
        if (nearby != null && nearby.response.status != DiscoveryStatus.UNAVAILABLE) {
            return nearby
        }
        val stored = if (dialStored) dialStoredEndpoints(target, pair, skip = matching.toSet()) else null
        return stored ?: nearby
    }

    /** Probes [endpoints] one after another and returns the first answer that is not unavailable, else the last. */
    private suspend fun probeInOrder(
        target: KnownHostTarget,
        pair: RememberedPair,
        endpoints: List<String>,
    ): KnownHostAnswer? {
        var last: KnownHostAnswer? = null
        for (endpoint in endpoints) {
            val response = probeAt(target, pair, endpoint) ?: continue
            last = KnownHostAnswer(response, KnownHostReach.NEARBY, endpoint)
            if (response.status != DiscoveryStatus.UNAVAILABLE) return last.also { recordAnswer(target, it) }
        }
        return last
    }

    /**
     * Dials the addresses this host was last seen at, together. The answer is the first in dial order that is not
     * unavailable; it is returned as soon as every probe ahead of it has finished, and the rest are cancelled.
     */
    private suspend fun dialStoredEndpoints(
        target: KnownHostTarget,
        pair: RememberedPair,
        skip: Set<String>,
    ): KnownHostAnswer? {
        val endpoints = target.dialEndpoints().filterNot { it in skip }
        return coroutineScope {
            val probes = endpoints.map { endpoint -> async { probeAt(target, pair, endpoint) } }
            for ((index, probe) in probes.withIndex()) {
                val response = probe.await()
                if (response != null && response.status != DiscoveryStatus.UNAVAILABLE) {
                    probes.drop(index + 1).forEach { it.cancel() }
                    return@coroutineScope KnownHostAnswer(response, KnownHostReach.AWAY, endpoints[index])
                        .also { recordAnswer(target, it) }
                }
            }
            null
        }
    }

    /** One probe of [endpoint], pinned to the target's known fingerprint; null when it failed or timed out. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun probeAt(target: KnownHostTarget, pair: RememberedPair, endpoint: String): DiscoverResponse? {
        val ticket = target.discoveryTicket(token = DISCOVER_TOKEN, endpoint = endpoint)
        return try {
            withTimeout(PROBE_TIMEOUT_MILLIS.milliseconds) { probe(target, ticket, pair) }
        } catch (timeout: TimeoutCancellationException) {
            discoveryWarn("[discovery] ${target.companionId.debugShortId()} timed out at $endpoint: ${timeout.message}")
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            discoveryWarn(
                "[discovery] ${target.companionId.debugShortId()} did not answer at $endpoint: ${failure.message}",
            )
            null
        }
    }

    private suspend fun recordAnswer(target: KnownHostTarget, answer: KnownHostAnswer) {
        answer.response.descriptor?.let { knownHosts.updateFromDescriptor(target, it, answer.endpoint) }
    }

    private suspend fun probe(target: KnownHostTarget, ticket: LinkTicket, pair: RememberedPair): DiscoverResponse {
        val lookup = DiscoveryLookup(companionId = target.companionId, pairId = pair.pairId)
        val response = http.discover(ticket, DiscoverRequest.Discover(lookup)) {
            LinkJson.decodeFromString(DiscoverResponse.serializer(), it)
        }
        discoveryDebug {
            "discover probe <- companion=${target.companionId.debugShortId()} status=${response.status} " +
                "challenge=${response.challenge?.challengeId.debugShortIdOrNone()} " +
                "blocked=${response.blockedMessage.debugQuotedOrNone()}"
        }
        return response
    }

    override suspend fun startDiscoverySession(
        target: KnownHostTarget,
        challenge: DiscoveryChallenge,
        endpoint: String,
        projectId: String?,
    ): DiscoveryConnectResult {
        val pair = rememberedPair(target) ?: run {
            discoveryDebug {
                "discovery connect skipped because no remembered pair exists " +
                    "companion=${target.companionId.debugShortId()}"
            }
            return DiscoveryConnectResult.Failed(status = DiscoveryStatus.UNAVAILABLE, blockedMessage = null)
        }
        val ticket = target.discoveryTicket(token = challenge.connectToken, endpoint = endpoint)
        val attemptId = deviceIdentity.nextAttemptId()
        discoveryDebug {
            "discovery connect -> companion=${target.companionId.debugShortId()} pair=${pair.pairId.debugShortId()} " +
                "challenge=${challenge.challengeId.debugShortId()} attempt=${attemptId.debugShortId()} " +
                "endpoint=${ticket.endpoint.debugQuotedOrNone()}"
        }
        EphemeralKeyAgreement().use { keyAgreement ->
            val attempt = DiscoveryAttempt(
                companionId = target.companionId,
                fingerprint = target.discoveryTransportFingerprint,
                route = LinkEndpoints.DISCOVER,
                challengeId = challenge.challengeId,
                attemptId = attemptId,
                serverChallenge = challenge.serverChallenge,
                deviceFingerprint = deviceIdentity.fingerprint,
                clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
            )
            val request = DiscoveryConnectRequest(
                companionId = target.companionId,
                pairId = pair.pairId,
                challengeId = challenge.challengeId,
                attemptId = attemptId,
                clientEphemeralPublicKey = keyAgreement.publicKeyBase64,
                clientPairProof = attempt.proof(pair.pairSecret),
                securityVersion = ProtocolVersions.SECURITY_VERSION,
                projectId = projectId,
            )
            val requestText = LinkJson.encodeToString(DiscoveryConnectRequest.serializer(), request)
            val connect = DiscoverRequest.Connect(requestText, http.discoveryProof(ticket, requestText))
            val response = http.discover(ticket, connect) {
                LinkJson.decodeFromString(DiscoveryConnectResponse.serializer(), it)
            }
            discoveryDebug {
                "discovery connect <- companion=${target.companionId.debugShortId()} status=${response.status} " +
                    "artifact=${response.encryptedArtifact != null} " +
                    "rotatedPair=${response.encryptedRotatedPair != null} " +
                    "blocked=${response.blockedMessage.debugQuotedOrNone()}"
            }
            return when (response.status) {
                DiscoveryStatus.PAIR_REVOKED -> {
                    rememberedPairs.store.removeByCompanionId(target.companionId)
                    knownHosts.remove(target.companionId)
                    discoveryDebug {
                        "discovery pair revoked companion=${target.companionId.debugShortId()} removedLocalState=true"
                    }
                    DiscoveryConnectResult.Failed(DiscoveryStatus.PAIR_REVOKED, response.blockedMessage)
                }

                DiscoveryStatus.AVAILABLE -> accept(target, ticket, response, keyAgreement, challenge, attempt)

                else -> {
                    discoveryDebug {
                        "discovery connect ended without artifact companion=${target.companionId.debugShortId()} " +
                            "status=${response.status}"
                    }
                    DiscoveryConnectResult.Failed(response.status, response.blockedMessage)
                }
            }
        }
    }

    /**
     * Opens the fresh ticket and the rotated pair. Both travel under the ECDH key rather than anything derived from
     * pairSecret, so a leaked pairSecret cannot unwrap a recorded connect afterwards.
     */
    internal suspend fun accept(
        target: KnownHostTarget,
        ticket: LinkTicket,
        response: DiscoveryConnectResponse,
        keyAgreement: EphemeralKeyAgreement,
        challenge: DiscoveryChallenge,
        attempt: DiscoveryAttempt,
    ): DiscoveryConnectResult {
        val responseKey = keyAgreement.responseKey(
            companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
            serverChallenge = challenge.serverChallenge,
            transcriptHash = attempt.transcriptHash(),
        )
        val accepted = responseKey?.let { key ->
            response.encryptedRotatedPair?.let { rotated ->
                val rotatedPair = openPairMaterial(rotated, key)
                rotatedPair?.let { rememberedPairs.store.save(ticket, it) }
                discoveryDebug {
                    "discovery rotated pair companion=${target.companionId.debugShortId()} saved=${rotatedPair != null}"
                }
            }
            response.encryptedArtifact?.let { openTicket(it, key) }
        } ?: return DiscoveryConnectResult.Failed(status = DiscoveryStatus.AVAILABLE, blockedMessage = null)
        discoveryDebug {
            "discovery artifact decrypted companion=${target.companionId.debugShortId()} " +
                "session=${accepted.sessionId.debugShortId()} endpoint=${accepted.endpoint.debugQuotedOrNone()}"
        }
        // Away from home every dead address in the ticket costs a hello timeout, so the answering one goes first and
        // the host's own primary and alternates follow it.
        val answeringFirst = accepted.copy(
            endpoint = ticket.endpoint,
            alternateEndpoints = (listOf(accepted.endpoint) + accepted.alternateEndpoints)
                .map { it.trimEnd('/') }
                .filter { it != ticket.endpoint.trimEnd('/') }
                .distinct(),
        )
        knownHosts.remember(answeringFirst, answeringFirst.companionId, answeringFirst.hostVersion)
        return DiscoveryConnectResult.Success(answeringFirst)
    }

    private fun rememberedPair(target: KnownHostTarget): RememberedPair? {
        val pair = rememberedPairs.store.load(target.discoveryTicket(token = PAIR_LOOKUP_TOKEN))
        discoveryDebug {
            if (pair == null) {
                "remembered pair missing target=${target.companionId.debugShortId()} " +
                    "lastUsedAt=${target.lastUsedAtEpochMillis}"
            } else {
                "remembered pair match target=${target.companionId.debugShortId()} " +
                    "pair=${pair.pairId.debugShortId()} expiresAt=${pair.expiresAtEpochMillis}"
            }
        }
        return pair
    }

    private companion object {
        const val DISCOVER_TOKEN = "discovery-discover"
        const val PAIR_LOOKUP_TOKEN = "remembered-pair-lookup"
    }
}

internal fun LocalNetworkServiceAdvertisement.discoveryEndpoint(): String {
    val normalizedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return "https://$normalizedHost:$port"
}

internal const val DISCOVERY_TIMEOUT_MILLIS = 3_000L

/** How long one discover probe may take before the next service advertising the host is tried. */
private const val PROBE_TIMEOUT_MILLIS = 2_500L
