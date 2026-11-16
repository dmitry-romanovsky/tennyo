package app.tennyo.host.engine.rpc

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.HostEditor
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkJson
import app.tennyo.contract.PairRequest
import app.tennyo.contract.RememberedPairResumeRequest
import app.tennyo.contract.SessionClaimRequest
import app.tennyo.contract.SessionConfirmPairingRequest
import app.tennyo.contract.SessionTimeouts
import app.tennyo.contract.SignedPairRequest
import app.tennyo.contract.wireName
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.SessionTicket
import app.tennyo.host.engine.session.TestDevice
import app.tennyo.host.engine.session.match
import app.tennyo.host.engine.session.openPairingWindow
import app.tennyo.host.engine.session.testPanes
import app.tennyo.host.engine.transport.ConnectionOrigin
import app.tennyo.host.engine.transport.HostHttpHeaderNames
import app.tennyo.host.engine.transport.HostHttpHeaders
import app.tennyo.host.engine.transport.HostHttpRequest
import app.tennyo.host.engine.transport.HostHttpResponse
import app.tennyo.host.engine.transport.HttpStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gates in front of pairing. Every one of them must fail closed: a request that gets past here can ask the user to
 * approve a device.
 */
class PublicEndpointsTest {
    private val scope = CoroutineScope(SupervisorJob())
    // With discovery on, a device the session already approved claims without waiting for the user.
    private val host = SessionHost(testPanes(), FakeHostPlatform(discoveryEnabled = true), scope)
    private val endpoints = PublicEndpoints(sessions = { id, token -> host.match(id, token) })

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `plain HTTP is refused before anything else`() = runTest {
        val response = endpoints.handle(request("GET", LinkEndpoints.HELLO, secure = false))

        assertEquals(HttpStatus.UPGRADE_REQUIRED, response.status)
        assertTrue(ErrorCodes.SECURE_TRANSPORT_REQUIRED in response.bodyText())
    }

    @Test
    fun `paths outside the three endpoints are not found`() = runTest {
        assertEquals(HttpStatus.NOT_FOUND, endpoints.handle(request("GET", "${LinkEndpoints.PREFIX}/../../etc/passwd")).status)
    }

    @Test
    fun `the channel is only reachable through a WebSocket upgrade`() = runTest {
        assertEquals(HttpStatus.BAD_REQUEST, endpoints.handle(request("GET", LinkEndpoints.CHANNEL)).status)
    }

    @Test
    fun `wrong method is refused and advertises the allowed one`() = runTest {
        val response = endpoints.handle(request("DELETE", LinkEndpoints.PAIR))

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.status)
        assertEquals("POST", response.header(HostHttpHeaderNames.ALLOW))
    }

    @Test
    fun `body over the endpoint limit is refused before it is parsed`() = runTest {
        val oversized = "x".repeat(LinkEndpoints.PAIR_BODY_LIMIT_BYTES + 1)

        val response = endpoints.handle(request("POST", LinkEndpoints.PAIR, body = oversized))

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status)
    }

    @Test
    fun `hello needs the session token`() = runTest {
        val ticket = host.startSession()

        val guessed = endpoints.handle(hello(ticket, token = "guessed"))
        val hello = endpoints.handle(hello(ticket, token = ticket.token))

        assertEquals(HttpStatus.UNAUTHORIZED, guessed.status)
        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in guessed.bodyText())
        assertEquals(ticket.projectName, LinkJson.decodeFromString(HandshakeResponse.serializer(), hello.bodyText()).projectName)
    }

    @Test
    fun `hello states the editor the host runs in`() = runTest {
        val ticket = host.startSession()

        val hello = endpoints.handle(hello(ticket, token = ticket.token))

        assertEquals(
            HostEditor.VS_CODE,
            LinkJson.decodeFromString(HandshakeResponse.serializer(), hello.bodyText()).editor,
        )
    }

    @Test
    fun `a claim whose body names another device than the proof is refused`() = runTest {
        val ticket = host.startSession()
        val signer = TestDevice()
        val claimed = TestDevice()
        val requestText = pairRequest(
            PairRequest.Claim(
                SessionClaimRequest(
                    sessionId = ticket.sessionId,
                    clientName = "Pixel",
                    clientVersion = "1.0",
                    devicePublicKey = claimed.publicKey,
                    deviceFingerprint = claimed.fingerprint,
                ),
            ),
        )

        val response = endpoints.handle(pair(ticket, requestText, signer))

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.WRONG_DEVICE.wireName in response.bodyText())
    }

    @Test
    fun `a replayed signed pairing request is refused`() = runTest {
        val ticket = host.startSession()
        val device = TestDevice()
        host.session(ticket.sessionId)!!.auth.approveDevice(device.publicKey, "Pixel", "request-1")
        val signed = pair(ticket, pairRequest(claim(ticket, device)), device)

        val first = endpoints.handle(signed)
        val replayed = endpoints.handle(signed)

        assertEquals(HttpStatus.OK, first.status, first.bodyText())
        assertEquals(HttpStatus.UNAUTHORIZED, replayed.status)
        assertTrue(LinkFaultCode.REPLAYED.wireName in replayed.bodyText(), replayed.bodyText())
    }

    @Test
    fun `confirming without an accepted connection request is refused as the wrong device`() = runTest {
        val ticket = host.startSession()
        val device = TestDevice()

        val response = endpoints.handle(pair(ticket, pairRequest(confirmation(ticket, device)), device))

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.WRONG_DEVICE.wireName in response.bodyText())
    }

    @Test
    fun `a pairing request for a stopped session finds no session`() = runTest {
        val ticket = host.startSession()
        val device = TestDevice()
        host.stopSession(ticket.sessionId, "Stopped in the IDE.")

        val response = endpoints.handle(pair(ticket, pairRequest(confirmation(ticket, device)), device))

        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in response.bodyText())
    }

    @Test
    fun `an unreadable pairing request is invalid`() = runTest {
        val response = endpoints.handle(request("POST", LinkEndpoints.PAIR, body = """{"sessionId":"s"}"""))

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `remote pairing token reads as no session`() = runTest {
        val window = host.openPairingWindow()
        val ticket = host.session(window.pairingSessionId)!!.artifact
        val device = TestDevice()
        host.session(ticket.sessionId)!!.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        val response = endpoints.handle(
            pairWithToken(ticket, pairRequest(claim(ticket, device)), device, window.pairingToken, REMOTE),
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in response.bodyText())
    }

    @Test
    fun `remote confirm is refused`() = runTest {
        val ticket = host.startSession()
        val device = TestDevice()

        val response = endpoints.handle(pair(ticket, pairRequest(confirmation(ticket, device)), device, REMOTE))

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.UNAUTHORIZED.wireName in response.bodyText(), response.bodyText())
    }

    @Test
    fun `remote resume-pair is refused`() = runTest {
        val ticket = host.startSession()
        val device = TestDevice()
        val resume = PairRequest.ResumePair(
            RememberedPairResumeRequest(
                sessionId = ticket.sessionId,
                pairId = "pair",
                challengeId = "challenge",
                attemptId = "attempt",
                clientEphemeralPublicKey = "client-ephemeral",
                clientPairProof = "proof",
            ),
        )

        val response = endpoints.handle(pair(ticket, pairRequest(resume), device, REMOTE))

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.UNAUTHORIZED.wireName in response.bodyText(), response.bodyText())
    }

    @Test
    fun `remote claim for an unapproved device is refused without a prompt`() = runTest {
        // Discovery is off, the one state in which a local claim for an approved device would still prompt.
        val quiet = QuietHost()
        val ticket = quiet.host.startSession()
        val device = TestDevice()

        val response = quiet.endpoints.handle(pair(ticket, pairRequest(claim(ticket, device)), device, REMOTE))

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.WRONG_DEVICE.wireName in response.bodyText())
        assertEquals(emptyList(), quiet.platform.shownRequests)
        assertEquals(emptyList(), quiet.host.pendingConnectionRequests(ticket.sessionId))
        quiet.close()
    }

    @Test
    fun `remote claim for the approved device is accepted without a prompt`() = runTest {
        val quiet = QuietHost()
        val ticket = quiet.host.startSession()
        val device = TestDevice()
        quiet.host.session(ticket.sessionId)!!.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        val response = quiet.endpoints.handle(pair(ticket, pairRequest(claim(ticket, device)), device, REMOTE))

        assertEquals(HttpStatus.OK, response.status, response.bodyText())
        assertEquals(emptyList(), quiet.platform.shownRequests)
        quiet.close()
    }

    @Test
    fun `an expired session on a public endpoint reads as no session`() = runTest {
        var now = hostWallClockMillis()
        val aging = SessionHost(testPanes(), FakeHostPlatform(), scope, wallClockMillis = { now })
        val aged = PublicEndpoints(sessions = { id, token -> aging.match(id, token) })
        val ticket = aging.startSession()
        now += SessionTimeouts.ABSOLUTE_SESSION_TIMEOUT_MILLIS + 1

        val response = aged.handle(hello(ticket, token = ticket.token, origin = REMOTE))

        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in response.bodyText(), response.bodyText())
        aging.close()
    }

    /** A host with discovery off, so a claim that reaches the approval step is visible as a shown request. */
    private inner class QuietHost {
        val platform = FakeHostPlatform(discoveryEnabled = false)
        val host = SessionHost(testPanes(), platform, scope)
        val endpoints = PublicEndpoints(sessions = { id, token -> host.match(id, token) })

        fun close() = host.close()
    }

    private fun claim(ticket: SessionTicket, device: TestDevice) =
        PairRequest.Claim(
            SessionClaimRequest(
                sessionId = ticket.sessionId,
                clientName = "Pixel",
                clientVersion = "1.0",
                devicePublicKey = device.publicKey,
                deviceFingerprint = device.fingerprint,
            ),
        )

    @Test
    fun `a pairing token does not open hello`() = runTest {
        val window = host.openPairingWindow()

        val response = endpoints.handle(
            request(
                method = "GET",
                path = LinkEndpoints.HELLO,
                query = mapOf(LinkEndpoints.SESSION_PARAMETER to listOf(window.pairingSessionId)),
                headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${window.pairingToken}"),
            ),
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in response.bodyText())
    }

    @Test
    fun `a pairing token reaches the claim step`() = runTest {
        val window = host.openPairingWindow()
        val ticket = host.session(window.pairingSessionId)!!.artifact
        val device = TestDevice()
        host.session(ticket.sessionId)!!.auth.approveDevice(device.publicKey, "Pixel", "request-1")
        val requestText = pairRequest(claim(ticket, device))

        val response = endpoints.handle(pairWithToken(ticket, requestText, device, window.pairingToken))

        assertEquals(HttpStatus.OK, response.status, response.bodyText())
    }

    @Test
    fun `a pairing token is refused once the window closes`() = runTest {
        val window = host.openPairingWindow()
        val ticket = host.session(window.pairingSessionId)!!.artifact
        val device = TestDevice()
        host.pairingWindow.close()

        val response = endpoints.handle(
            pairWithToken(ticket, pairRequest(claim(ticket, device)), device, window.pairingToken),
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
        assertTrue(LinkFaultCode.SESSION_NOT_FOUND.wireName in response.bodyText())
    }

    @Test
    fun `a proof signed under the session token is refused when the pairing token authenticated the request`() =
        runTest {
            val window = host.openPairingWindow()
            val ticket = host.session(window.pairingSessionId)!!.artifact
            val device = TestDevice()
            val requestText = pairRequest(claim(ticket, device))

            // Signed over the session token's hash, but sent with the pairing token: the binding must reject it.
            val response = endpoints.handle(
                request(
                    method = "POST",
                    path = LinkEndpoints.PAIR,
                    body = LinkJson.encodeToString(
                        SignedPairRequest.serializer(),
                        SignedPairRequest(
                            sessionId = ticket.sessionId,
                            request = requestText,
                            proof = device.proof(ticket, LinkEndpoints.PAIR, requestText, hostWallClockMillis()),
                        ),
                    ),
                    headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${window.pairingToken}"),
                ),
            )

            assertEquals(HttpStatus.UNAUTHORIZED, response.status)
            assertTrue(LinkFaultCode.UNAUTHORIZED.wireName in response.bodyText())
        }

    @Test
    fun `a pairing token does not reach discovery, which takes no bearer token at all`() = runTest {
        val window = host.openPairingWindow()

        // Discovery answers a remembered pair's proof or a pairing offer; it never reads an Authorization header,
        // so a window's credential buys nothing here.
        val response = endpoints.handle(
            request(
                method = "POST",
                path = LinkEndpoints.DISCOVER,
                body = LinkJson.encodeToString(DiscoverRequest.serializer(), DiscoverRequest.Offer),
                headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${window.pairingToken}"),
            ),
        )

        // No DiscoveryRoutes on this endpoints instance, so the path is simply not there to authenticate against.
        assertEquals(HttpStatus.NOT_FOUND, response.status)
    }

    private fun pairWithToken(
        ticket: SessionTicket,
        requestText: String,
        device: TestDevice,
        token: String,
        origin: ConnectionOrigin = ConnectionOrigin.LOCAL,
    ): HostHttpRequest =
        request(
            origin = origin,
            method = "POST",
            path = LinkEndpoints.PAIR,
            body = LinkJson.encodeToString(
                SignedPairRequest.serializer(),
                SignedPairRequest(
                    sessionId = ticket.sessionId,
                    request = requestText,
                    proof = device.proof(
                        ticket = ticket,
                        endpoint = LinkEndpoints.PAIR,
                        requestText = requestText,
                        signedAt = hostWallClockMillis(),
                        authenticatedToken = token,
                    ),
                ),
            ),
            headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer $token"),
        )

    private fun confirmation(ticket: SessionTicket, device: TestDevice) =
        PairRequest.Confirm(
            SessionConfirmPairingRequest(
                sessionId = ticket.sessionId,
                devicePublicKey = device.publicKey,
                deviceFingerprint = device.fingerprint,
                challengeId = "challenge",
                attemptId = "attempt",
                clientEphemeralPublicKey = "client-ephemeral",
                clientPairingProof = "proof",
            ),
        )

    private fun pairRequest(request: PairRequest): String = LinkJson.encodeToString(PairRequest.serializer(), request)

    private fun pair(
        ticket: SessionTicket,
        requestText: String,
        device: TestDevice,
        origin: ConnectionOrigin = ConnectionOrigin.LOCAL,
    ): HostHttpRequest =
        request(
            origin = origin,
            method = "POST",
            path = LinkEndpoints.PAIR,
            body = LinkJson.encodeToString(
                SignedPairRequest.serializer(),
                SignedPairRequest(
                    sessionId = ticket.sessionId,
                    request = requestText,
                    proof = device.proof(ticket, LinkEndpoints.PAIR, requestText, hostWallClockMillis()),
                ),
            ),
            headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer ${ticket.token}"),
        )

    private fun hello(
        ticket: SessionTicket,
        token: String,
        origin: ConnectionOrigin = ConnectionOrigin.LOCAL,
    ): HostHttpRequest =
        request(
            origin = origin,
            method = "GET",
            path = LinkEndpoints.HELLO,
            query = mapOf(LinkEndpoints.SESSION_PARAMETER to listOf(ticket.sessionId)),
            headers = listOf(HostHttpHeaderNames.AUTHORIZATION to "Bearer $token"),
        )

    private fun request(
        method: String,
        path: String,
        body: String = "",
        query: Map<String, List<String>> = emptyMap(),
        headers: List<Pair<String, String>> = emptyList(),
        secure: Boolean = true,
        origin: ConnectionOrigin = ConnectionOrigin.LOCAL,
    ): HostHttpRequest =
        HostHttpRequest(
            origin = origin,
            method = method,
            path = path,
            queryParameters = query,
            headers = HostHttpHeaders(headers),
            body = body.encodeToByteArray(),
            isSecure = secure,
        )

    private fun HostHttpResponse.bodyText(): String = body.decodeToString()

    private fun HostHttpResponse.header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private companion object {
        val REMOTE = ConnectionOrigin.REMOTE
    }
}
