package app.tennyo.host.engine.discovery

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryConnectRequest
import app.tennyo.contract.DiscoveryConnectResponse
import app.tennyo.contract.DiscoveryLookup
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.ProjectChoice
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.SessionClaimRequest
import app.tennyo.contract.SessionTimeouts
import app.tennyo.contract.SignedPairRequest
import app.tennyo.contract.wireName
import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.crypto.sharedSecret
import app.tennyo.host.engine.pairing.DiscoveryAttempt
import app.tennyo.host.engine.pairing.RememberedPairMaterial
import app.tennyo.host.engine.pairing.discoveryPairProof
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.engine.pairing.unprotectPairMaterial
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.rpc.PublicEndpoints
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.OverlayEndpoint
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.SessionTicket
import app.tennyo.host.engine.session.TestChannel
import app.tennyo.host.engine.session.TestDevice
import app.tennyo.host.engine.session.match
import app.tennyo.host.engine.session.testPanes
import app.tennyo.host.engine.transport.ConnectionOrigin
import app.tennyo.host.engine.transport.HostHttpHeaderNames
import app.tennyo.host.engine.transport.HostHttpHeaders
import app.tennyo.host.engine.transport.HostHttpRequest
import app.tennyo.host.engine.transport.HttpStatus
import app.tennyo.host.engine.transport.OverlayNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone reconnecting by remembered pair, played against the real discovery routes from a remote origin: discover,
 * connect, then the hello and claim that open its session. The phone's side is written out here the way the app does
 * it, so a change to the exchange that the app would not survive fails here first.
 */
class DiscoveryConnectTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val clock = AtomicLong(hostWallClockMillis())
    private val platform = FakeHostPlatform(discoveryEnabled = true, remoteAccess = true)
    private val host = SessionHost(testPanes(), platform, scope, clock::load)
    private val trustStore = object : HostSettingsStore {
        override var trust = DiscoveryTrustState(enabled = true, remoteAccess = true)
    }
    private val lockout = DiscoveryLockoutPolicy(trustStore)
    private val routes = DiscoveryRoutes(Directory())
    private val endpoints = PublicEndpoints(routes) { sessionId, token -> host.match(sessionId, token) }
    private val device = TestDevice()
    private var attempts = 0

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `a discovery connect rotation yields a pair expiring after the contract TTL`() {
        val pair = remember()
        val before = hostWallClockMillis()

        val rotated = checkNotNull(connect(pair).rotatedPair)

        val after = hostWallClockMillis()
        val ttl = SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS
        assertTrue(rotated.expiresAtEpochMillis in (before + ttl)..(after + ttl), "${rotated.expiresAtEpochMillis}")
    }

    @Test
    fun `a connect whose response is dropped is retried with the old pair once, and then the old pair dies`() {
        val original = remember()
        val dropped = connect(original)
        val lost = checkNotNull(dropped.rotatedPair)

        val retried = connect(original)
        val current = checkNotNull(retried.rotatedPair)

        assertEquals(DiscoveryStatus.AVAILABLE, retried.response.status)
        assertNotNull(retried.ticket)
        assertEquals(DiscoveryStatus.UNAVAILABLE, discover(original).status, "the old pair is spent")
        assertEquals(DiscoveryStatus.UNAVAILABLE, discover(lost.held()).status, "the lost pair is spent")
        assertEquals(DiscoveryStatus.AVAILABLE, discover(current.held()).status)
        assertEquals(DiscoveryStatus.PAIR_REVOKED, connectRaw(original, NO_CHALLENGE).status)
    }

    @Test
    fun `the old pair stops working once the new pair is first used`() {
        val original = remember()
        val first = checkNotNull(connect(original).rotatedPair)

        connect(first.held())

        assertEquals(DiscoveryStatus.UNAVAILABLE, discover(original).status)
    }

    @Test
    fun `the old pair is kept for no longer than one challenge lifetime`() {
        val original = remember()
        connect(original)

        val graceEnds = checkNotNull(host.platform.pairStore.find(original.pairId)).expiresAtEpochMillis

        assertTrue(graceEnds - hostWallClockMillis() <= DiscoveryChallengeStore.CHALLENGE_TTL_MILLIS)
    }

    @Test
    fun `the old pair is not listed as a second device`() {
        val original = remember()
        connect(original)

        assertEquals(1, host.platform.pairStore.summaries().size)
    }

    @Test
    fun `a replayed connect attempt answers a discovery status and is counted`() {
        val pair = remember()
        val challenge = checkNotNull(discover(pair).challenge)
        assertEquals(DiscoveryStatus.AVAILABLE, connectRaw(pair, challenge, attemptId = "attempt").status)

        val replayed = connectRaw(pair, challenge, attemptId = "attempt")

        assertEquals(DiscoveryStatus.UNAVAILABLE, replayed.status)
        assertNull(replayed.encryptedArtifact)
        assertEquals(1, trustStore.trust.countedFailureEpochMillis.size)
    }

    @Test
    fun `a blocking run of failures turns remote access off`() {
        val pair = remember()
        repeat(DiscoveryLockoutPolicy.MAX_FAILURES) {
            connectRaw(pair, NO_CHALLENGE)
        }

        assertEquals(false, lockout.isRemoteAccessEnabled())
    }

    @Test
    fun `reconnect works end to end from a remote origin`() = runTest {
        val pair = remember()

        val ticket = checkNotNull(connect(pair).ticket)
        val hello = endpoints.handle(helloRequest(ticket))
        val claim = endpoints.handle(claimRequest(ticket))
        val channel = TestChannel(host, ticket, device, clock::load).open()

        assertEquals(HttpStatus.OK, hello.status, hello.body.decodeToString())
        assertEquals(HttpStatus.OK, claim.status, claim.body.decodeToString())
        assertNotNull(channel.opened)
        assertEquals(emptyList(), platform.shownRequests)
    }

    @Test
    fun `an address that appears after a session started is in the next connect's ticket`() {
        val pair = remember()
        val first = checkNotNull(connect(pair))
        assertEquals(emptyList(), first.ticket?.alternateEndpoints)

        val overlayUrl = "https://100.101.2.3:63342/api/tennyo"
        platform.endpoints = platform.endpoints.copy(
            overlay = listOf(OverlayEndpoint(OverlayNetwork.TAILNET_RANGE, "100.101.2.3", overlayUrl)),
        )
        val second = connect(checkNotNull(first.rotatedPair).held())

        assertEquals(listOf(overlayUrl), second.ticket?.alternateEndpoints)
    }

    @Test
    fun `a stopped session reads as no session from a remote origin`() = runTest {
        val ticket = checkNotNull(connect(remember()).ticket)
        host.stopSession(ticket.sessionId, "Stopped in the editor.")

        val hello = endpoints.handle(helloRequest(ticket))
        val claim = endpoints.handle(claimRequest(ticket))

        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in hello.body.decodeToString())
        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in claim.body.decodeToString())
    }

    @Test
    fun `a session past its absolute deadline answers session_expired on the channel open`() {
        val ticket = checkNotNull(connect(remember()).ticket)
        val deadline = checkNotNull(host.session(ticket.sessionId)).expiresAtEpochMillis
        // The device keeps the session alive by reopening within the idle window, as a phone on a flaky link does.
        while (clock.load() + REOPEN_STEP_MILLIS < deadline) {
            clock.addAndFetch(REOPEN_STEP_MILLIS)
            TestChannel(host, ticket, device, clock::load).open()
        }
        clock.addAndFetch(deadline - clock.load())

        val channel = TestChannel(host, ticket, device, clock::load).open()

        assertEquals(
            LinkFaultCode.SESSION_EXPIRED,
            channel.socket.frames.filterIsInstance<LinkFrame.Fault>().lastOrNull()?.fault,
        )
    }

    @Test
    fun `another device cannot claim, or open the channel of, the connected session`() = runTest {
        val ticket = checkNotNull(connect(remember()).ticket)
        val stranger = TestDevice()

        val claim = endpoints.handle(claimRequest(ticket, stranger))
        val channel = TestChannel(host, ticket, stranger, clock::load).open()

        assertTrue(LinkFaultCode.WRONG_DEVICE.wireName in claim.body.decodeToString())
        assertEquals(
            LinkFaultCode.WRONG_DEVICE,
            channel.socket.frames.filterIsInstance<LinkFrame.Fault>().lastOrNull()?.fault,
        )
    }

    private fun remember(): HeldPair {
        val (material, record) = newRememberedPair("Pixel", device.fingerprint)
        host.saveRememberedPair(record.copy(companionId = COMPANION))
        return HeldPair(material.pairId, material.pairSecret)
    }

    private fun RememberedPairMaterial.held() = HeldPair(pairId, pairSecret)

    private fun discover(pair: HeldPair): DiscoverResponse =
        LinkJson.decodeFromString(
            DiscoverResponse.serializer(),
            routes.handle(DiscoverRequest.Discover(DiscoveryLookup(COMPANION, pair.pairId)), SOURCE, REMOTE),
        )

    /** Discover, then connect with the answer: what the phone does on every reconnect. */
    private fun connect(pair: HeldPair): Connected {
        val challenge = checkNotNull(discover(pair).challenge) { "discover gave no challenge" }
        return connectExchange(pair, challenge, "attempt-${++attempts}")
    }

    private fun connectRaw(
        pair: HeldPair,
        challenge: DiscoveryChallenge = checkNotNull(discover(pair).challenge),
        attemptId: String = "attempt-${++attempts}",
    ): DiscoveryConnectResponse = connectExchange(pair, challenge, attemptId).response

    private fun connectExchange(pair: HeldPair, challenge: DiscoveryChallenge, attemptId: String): Connected =
        EphemeralKeyAgreement().use { agreement ->
            val attempt = DiscoveryAttempt(
                companionId = COMPANION,
                fingerprint = FINGERPRINT,
                route = LinkEndpoints.DISCOVER,
                challengeId = challenge.challengeId,
                attemptId = attemptId,
                serverChallenge = challenge.serverChallenge,
                deviceFingerprint = device.fingerprint,
                clientEphemeralPublicKey = agreement.publicKeyBase64,
                companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
            )
            val request = DiscoveryConnectRequest(
                companionId = COMPANION,
                pairId = pair.pairId,
                challengeId = challenge.challengeId,
                attemptId = attemptId,
                clientEphemeralPublicKey = agreement.publicKeyBase64,
                clientPairProof = discoveryPairProof(pair.pairSecret, attempt),
                securityVersion = ProtocolVersions.SECURITY_VERSION,
            )
            val requestText = LinkJson.encodeToString(DiscoveryConnectRequest.serializer(), request)
            val connectTicket = SessionTicket(
                endpoint = platform.endpoints.primary,
                sessionId = COMPANION,
                token = challenge.connectToken,
                fingerprint = FINGERPRINT,
                projectName = "Tennyo",
                projectPath = "~/tennyo",
                capabilities = emptySet(),
            )
            val proof = device.proof(connectTicket, LinkEndpoints.DISCOVER, requestText, hostWallClockMillis())
            val response = LinkJson.decodeFromString(
                DiscoveryConnectResponse.serializer(),
                routes.handle(DiscoverRequest.Connect(requestText, proof), SOURCE, REMOTE),
            )
            val responseKey = agreement.sharedSecret(challenge.companionEphemeralPublicKey)?.let {
                SecurityDerivations.pairMaterialKey(it, challenge.serverChallenge, attempt.transcriptHash())
            }
            Connected(response, responseKey)
        }

    private class Connected(val response: DiscoveryConnectResponse, responseKey: ByteArray?) {
        val ticket: SessionTicket? = response.encryptedArtifact
            ?.let { sealed -> responseKey?.let { ProtectedPayloads.open(sealed, it) } }
            ?.let { HostJson.decodeFromString<SessionTicket>(it.decodeToString()) }

        val rotatedPair: RememberedPairMaterial? = response.encryptedRotatedPair
            ?.let { sealed -> responseKey?.let { unprotectPairMaterial(sealed, it) } }
    }

    private class HeldPair(val pairId: String, val pairSecret: String)

    private fun helloRequest(ticket: SessionTicket) = HostHttpRequest(
        method = "GET",
        path = LinkEndpoints.HELLO,
        queryParameters = mapOf(LinkEndpoints.SESSION_PARAMETER to listOf(ticket.sessionId)),
        headers = HostHttpHeaders(listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${ticket.token}")),
        origin = REMOTE,
    )

    private fun claimRequest(ticket: SessionTicket, claimant: TestDevice = device): HostHttpRequest {
        val claim = PairRequest.Claim(
            SessionClaimRequest(
                sessionId = ticket.sessionId,
                clientName = "Pixel",
                clientVersion = "1.0",
                devicePublicKey = claimant.publicKey,
                deviceFingerprint = claimant.fingerprint,
            ),
        )
        val requestText = LinkJson.encodeToString(PairRequest.serializer(), claim)
        val signed = SignedPairRequest(
            sessionId = ticket.sessionId,
            request = requestText,
            proof = claimant.proof(ticket, LinkEndpoints.PAIR, requestText, hostWallClockMillis()),
        )
        return HostHttpRequest(
            method = "POST",
            path = LinkEndpoints.PAIR,
            headers = HostHttpHeaders(listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${ticket.token}")),
            body = LinkJson.encodeToString(SignedPairRequest.serializer(), signed).encodeToByteArray(),
            origin = REMOTE,
        )
    }

    /** One project, answering discovery under the lockout this test owns and listening where the fake platform says. */
    private inner class Project : DiscoveryProject {
        override val projectId: String = "project"
        override val host: SessionHost get() = this@DiscoveryConnectTest.host

        override fun isDiscoveryEnabled(): Boolean = lockout.isEnabled()

        override fun isDiscoveryAvailable(): Boolean = true

        override fun discoveryBlockedMessage(): String? = lockout.blockedMessage()

        override fun discoveryProjectChoices(): List<ProjectChoice> =
            listOf(ProjectChoice(projectId = "project", projectName = "Tennyo", projectPath = "~/tennyo"))

        override fun discoveryDescriptor(): KnownHostDescriptor = KnownHostDescriptor(
            companionId = COMPANION,
            companionLabel = "Test IDE",
            projectName = "Tennyo",
            hostVersion = "1.0",
            lastUsedAtEpochMillis = 0L,
        )

        override fun discoveryEndpoints(): HostEndpoints = platform.endpoints

        override fun recordDiscoverySecurityFailure(message: String, attackerSource: String?): Boolean =
            lockout.recordSecurityFailure(message, attackerSource)
    }

    private inner class Directory : ProjectDirectory {
        private val project = Project()

        override fun discoveryOwner(companionId: String): DiscoveryProject? =
            project.takeIf { companionId == COMPANION && it.isDiscoveryEnabled() }

        override fun pairingWindowOwner(): DiscoveryProject? = null

        override fun project(projectId: String): DiscoveryProject = project

        override fun message(message: DiscoveryMessage): String = message.name
    }

    private companion object {
        const val COMPANION = "companion"
        const val FINGERPRINT = "fingerprint"
        const val SOURCE = "203.0.113.5"

        /** What a phone holds when it never asked, or when the host forgot its challenge. */
        val NO_CHALLENGE = DiscoveryChallenge("missing", "server-challenge", "token", "unreadable-key", 0L)
        val REMOTE = ConnectionOrigin.REMOTE
        const val REOPEN_STEP_MILLIS = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS - 10_000L
    }
}
