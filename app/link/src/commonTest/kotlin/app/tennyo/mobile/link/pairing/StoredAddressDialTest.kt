package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryConnectResponse
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkJson
import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.link.transport.installLinkErrorHandling
import app.tennyo.mobile.link.transport.isTransportEndpointPinned
import app.tennyo.mobile.link.transport.transportPinFingerprint
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class StoredAddressDialTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestedHosts = mutableListOf<String>()
    private val deadHosts = mutableSetOf<String>()
    private val hangingHosts = mutableSetOf<String>()
    private val statusByHost = mutableMapOf<String, DiscoveryStatus>()
    private val targetStore = SettingsKnownHostTargetStore(MapSettings())
    private val identity = DeviceIdentity()
    /** What the host would put in the connect's sealed ticket: its LAN address first, the overlay one after. */
    private val hostTicket = LinkTicket(
        endpoint = LAN_ENDPOINT,
        sessionId = "session-1",
        token = "session-token",
        fingerprint = FINGERPRINT,
        projectName = "Tennyo",
        projectPath = "/tennyo",
        alternateEndpoints = listOf(OVERLAY_ENDPOINT),
        companionId = COMPANION_ID,
        hostVersion = "2026.3",
    )

    private val client = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requestedHosts += request.url.host
                if (request.url.host in deadHosts) error("no route to host")
                if (request.url.host in hangingHosts) awaitCancellation()
                respond(
                    content = json.encodeToString(DiscoverResponse(statusByHost[request.url.host] ?: DiscoveryStatus.AVAILABLE, descriptor = DESCRIPTOR)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        install(ContentNegotiation) { json(json) }
        installLinkErrorHandling(json)
    }

    private val knownHosts = KnownHosts(targetStore, FixedPairStore)
    private val discovery = LocalDiscovery(
        http = LinkHttp(client, identity),
        deviceIdentity = identity,
        rememberedPairs = RememberedPairs(FixedPairStore),
        knownHosts = knownHosts,
        gateway = NoopLocalNetworkDiscoveryGateway,
    )

    @Test
    fun `a known host missing from DNS-SD is reached at its stored ticket address`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT))
        deadHosts += LAN_HOST

        val answer = assertNotNull(discovery.discoverCandidates(listOf(target), emptyList(), dialStored = true)[COMPANION_ID])

        assertEquals(KnownHostReach.AWAY, answer.reach)
        assertEquals(DiscoveryStatus.AVAILABLE, answer.response.status)
        assertEquals(OVERLAY_ENDPOINT, targetStore.get(COMPANION_ID)?.lastResolvedHost)
        assertTrue(isTransportEndpointPinned(OVERLAY_ENDPOINT))
    }

    @Test
    fun `stored addresses are not dialed unless the pass asks for it`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(OVERLAY_ENDPOINT))

        val answers = discovery.discoverCandidates(listOf(target), emptyList(), dialStored = false)

        assertNull(answers[COMPANION_ID])
        assertTrue(requestedHosts.isEmpty())
    }

    @Test
    fun `a host answering on both paths is reported NEARBY`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(OVERLAY_ENDPOINT))
        val service = LocalNetworkServiceAdvertisement(
            serviceType = app.tennyo.contract.Discovery.SERVICE_TYPE,
            serviceInstanceId = COMPANION_ID,
            host = LAN_HOST,
            port = PORT,
        )

        val answer = assertNotNull(discovery.discoverCandidates(listOf(target), listOf(service), dialStored = true)[COMPANION_ID])

        assertEquals(KnownHostReach.NEARBY, answer.reach)
        assertEquals(listOf(LAN_HOST), requestedHosts)
    }

    @Test
    fun `the answering endpoint leads the stored dial order and the ticket handed on`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT))
        deadHosts += LAN_HOST
        discovery.discoverCandidate(target)

        assertEquals(OVERLAY_ENDPOINT, targetStore.get(COMPANION_ID)?.dialEndpoints()?.first())
        assertEquals(OVERLAY_ENDPOINT, hostTicket.withEndpoint(OVERLAY_ENDPOINT).endpointCandidates().first())
    }

    @Test
    fun `an overlay answer leads the ticket and keeps the LAN address stored`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT))

        val returned = assertIs<DiscoveryConnectResult.Success>(connect(target, answeredAt = OVERLAY_ENDPOINT)).artifact

        assertEquals(listOf(OVERLAY_ENDPOINT, LAN_ENDPOINT), returned.endpointCandidates())
        assertEquals(setOf(LAN_ENDPOINT, OVERLAY_ENDPOINT), targetStore.get(COMPANION_ID)?.ticketEndpoints?.toSet())
        assertEquals(OVERLAY_ENDPOINT, targetStore.get(COMPANION_ID)?.dialEndpoints()?.first())
    }

    @Test
    fun `a typed address that answers never becomes a ticket address and stops being dialed once removed`() =
        runBlocking {
            val target = knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT), addedEndpoints = listOf(ADDED_ENDPOINT))

            val returned = assertIs<DiscoveryConnectResult.Success>(connect(target, answeredAt = ADDED_ENDPOINT)).artifact

            assertEquals(ADDED_ENDPOINT, returned.endpointCandidates().first())
            val stored = assertNotNull(targetStore.get(COMPANION_ID))
            assertEquals(ADDED_ENDPOINT, stored.lastResolvedHost)
            assertFalse(ADDED_ENDPOINT in stored.ticketEndpoints)

            knownHosts.removeEndpoint(COMPANION_ID, ADDED_ENDPOINT)

            assertFalse(ADDED_ENDPOINT in assertNotNull(targetStore.get(COMPANION_ID)).dialEndpoints())
        }

    @Test
    fun `an earlier stored address that answers does not wait for a later one that hangs`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(OVERLAY_ENDPOINT, LAN_ENDPOINT))
        hangingHosts += LAN_HOST

        val started = TimeSource.Monotonic.markNow()
        val answer = assertNotNull(discovery.discoverCandidates(listOf(target), emptyList(), dialStored = true)[COMPANION_ID])

        assertEquals(OVERLAY_ENDPOINT, answer.endpoint)
        assertTrue(started.elapsedNow() < 2.seconds)
    }

    /** The host's half of a connect, answered at [answeredAt]: its ticket sealed under the agreed ephemeral key. */
    private suspend fun connect(target: KnownHostTarget, answeredAt: String): DiscoveryConnectResult {
        val companionKey = EphemeralKeyAgreement()
        val phoneKey = EphemeralKeyAgreement()
        val challenge = DiscoveryChallenge(
            challengeId = "challenge-1",
            serverChallenge = "server-challenge",
            connectToken = "connect-token",
            companionEphemeralPublicKey = companionKey.publicKeyBase64,
            expiresAtEpochMillis = Long.MAX_VALUE,
        )
        val attempt = DiscoveryAttempt(
            companionId = COMPANION_ID,
            fingerprint = FINGERPRINT,
            route = LinkEndpoints.DISCOVER,
            challengeId = challenge.challengeId,
            attemptId = "attempt-1",
            serverChallenge = challenge.serverChallenge,
            deviceFingerprint = "device-fingerprint",
            clientEphemeralPublicKey = phoneKey.publicKeyBase64,
            companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
        )
        val hostKey = assertNotNull(
            companionKey.responseKey(phoneKey.publicKeyBase64, challenge.serverChallenge, attempt.transcriptHash()),
        )
        val sealed = ProtectedPayloads.seal(
            plaintext = LinkJson.encodeToString(LinkTicket.serializer(), hostTicket).encodeToByteArray(),
            responseKey = hostKey,
            nonce = secureRandomBytes(NONCE_BYTES).joinToString("") { (it.toInt() and 0xFF).toString(HEX).padStart(2, '0') },
        )
        return discovery.accept(
            target = target,
            ticket = target.discoveryTicket(token = challenge.connectToken, endpoint = answeredAt),
            response = DiscoveryConnectResponse(DiscoveryStatus.AVAILABLE, encryptedArtifact = sealed),
            keyAgreement = phoneKey,
            challenge = challenge,
            attempt = attempt,
        )
    }

    @Test
    fun `a stored address is pinned to the target's known fingerprint`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(OVERLAY_ENDPOINT))

        discovery.discoverCandidate(target)

        assertEquals(FINGERPRINT, transportPinFingerprint(OVERLAY_ENDPOINT))
    }

    @Test
    fun `an added address is pinned to the known fingerprint and dialed after ticket addresses`() = runBlocking {
        val target = knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT), addedEndpoints = listOf(ADDED_ENDPOINT))
        deadHosts += LAN_HOST

        val answer = assertNotNull(discovery.discoverCandidates(listOf(target), emptyList(), dialStored = true)[COMPANION_ID])

        assertEquals(listOf(LAN_ENDPOINT, ADDED_ENDPOINT), target.dialEndpoints())
        assertEquals(KnownHostReach.AWAY, answer.reach)
        assertEquals(FINGERPRINT, transportPinFingerprint(ADDED_ENDPOINT))
    }

    @Test
    fun `checkAddress maps answers to REACHED and REFUSED and NO_ANSWER`() = runBlocking {
        val target = knownTarget()
        statusByHost[LAN_HOST] = DiscoveryStatus.BLOCKED
        statusByHost[OVERLAY_HOST] = DiscoveryStatus.PAIR_REVOKED
        deadHosts += ADDED_HOST

        assertEquals(AddressCheck.REACHED, discovery.checkAddress(target, "https://$DEFAULT_HOST:$PORT"))
        assertEquals(AddressCheck.REACHED, discovery.checkAddress(target, LAN_ENDPOINT))
        assertEquals(AddressCheck.REFUSED, discovery.checkAddress(target, OVERLAY_ENDPOINT))
        assertEquals(AddressCheck.NO_ANSWER, discovery.checkAddress(target, ADDED_ENDPOINT))
    }

    @Test
    fun `added addresses survive a reconnect and a descriptor update`() = runBlocking<Unit> {
        targetStore.save(knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT)))
        knownHosts.addEndpoint(COMPANION_ID, ADDED_ENDPOINT)

        knownHosts.remember(hostTicket, COMPANION_ID, "2026.3")
        targetStore.updateFromDescriptor(DESCRIPTOR, FINGERPRINT, OVERLAY_ENDPOINT)

        assertEquals(listOf(ADDED_ENDPOINT), targetStore.get(COMPANION_ID)?.addedEndpoints)
        assertEquals(listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT), targetStore.get(COMPANION_ID)?.ticketEndpoints)

        knownHosts.removeEndpoint(COMPANION_ID, ADDED_ENDPOINT)
        assertEquals(emptyList(), targetStore.get(COMPANION_ID)?.addedEndpoints)
    }

    @Test
    fun `ticket addresses survive a descriptor update and are replaced only by a newer ticket`() = runBlocking<Unit> {
        targetStore.save(knownTarget(ticketEndpoints = listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT)))

        targetStore.updateFromDescriptor(DESCRIPTOR, FINGERPRINT, OVERLAY_ENDPOINT)
        assertEquals(listOf(LAN_ENDPOINT, OVERLAY_ENDPOINT), targetStore.get(COMPANION_ID)?.ticketEndpoints)

        knownHosts.remember(hostTicket.copy(alternateEndpoints = emptyList()), COMPANION_ID, "2026.3")
        assertEquals(listOf(LAN_ENDPOINT), targetStore.get(COMPANION_ID)?.ticketEndpoints)
        assertFalse(targetStore.get(COMPANION_ID)?.dialEndpoints().orEmpty().isEmpty())
    }

    private fun knownTarget(
        ticketEndpoints: List<String> = emptyList(),
        addedEndpoints: List<String> = emptyList(),
    ): KnownHostTarget =
        KnownHostTarget(
            companionId = COMPANION_ID,
            companionLabel = "Studio",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = app.tennyo.mobile.link.pane.nowEpochMillis(),
            discoveryTransportFingerprint = FINGERPRINT,
            ticketEndpoints = ticketEndpoints,
            addedEndpoints = addedEndpoints,
        ).also(targetStore::save)

    private companion object {
        const val COMPANION_ID = "companion-1"
        const val FINGERPRINT = "fingerprint"
        const val PORT = 63343
        const val NONCE_BYTES = 16
        const val HEX = 16
        const val LAN_HOST = "192.168.1.41"
        const val OVERLAY_HOST = "100.101.2.3"
        const val ADDED_HOST = "203.0.113.5"
        const val DEFAULT_HOST = "192.168.1.99"
        const val LAN_ENDPOINT = "https://$LAN_HOST:$PORT"
        const val ADDED_ENDPOINT = "https://$ADDED_HOST:$PORT"
        const val OVERLAY_ENDPOINT = "https://$OVERLAY_HOST:$PORT"
        val DESCRIPTOR = KnownHostDescriptor(
            companionId = COMPANION_ID,
            companionLabel = "Studio",
            projectName = "Tennyo",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = 1L,
        )
    }
}

/** Holds one valid pair for every ticket, so probes get as far as the network. */
private object FixedPairStore : RememberedPairStore {
    private val pair = RememberedPair(pairId = "pair-1", pairSecret = "secret", expiresAtEpochMillis = Long.MAX_VALUE)
    override fun load(artifact: LinkTicket): RememberedPair = pair
    override fun save(artifact: LinkTicket, material: RememberedPairMaterial) = Unit
    override fun remove(artifact: LinkTicket) = Unit
    override fun removeByCompanionId(companionId: String) = Unit
}
