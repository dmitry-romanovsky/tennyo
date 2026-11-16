package app.tennyo.host.engine.discovery

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.LinkJson
import app.tennyo.contract.OfferResponse
import app.tennyo.contract.PairingOfferStatus
import app.tennyo.contract.ProjectChoice
import app.tennyo.crypto.HostShortId
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.openPairingWindow
import app.tennyo.host.engine.session.testPanes
import app.tennyo.host.engine.transport.ConnectionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a phone that has never spoken to this host is told. An offer says as little as it can, and it says nothing at
 * all unless a window is open — the project's name and capabilities wait for the user's approval.
 */
class PairingOfferRouteTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val host = SessionHost(testPanes(), FakeHostPlatform(discoveryEnabled = true), scope)
    private var blockedMessage: String? = null
    private val routes = DiscoveryRoutes(TestDirectory())

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `reports closed while no window is open`() {
        assertEquals(PairingOfferStatus.CLOSED, offer().status)
        assertNull(offer().offer)
    }

    @Test
    fun `answers an open window with its name and derived id`() {
        val window = host.openPairingWindow()

        val response = offer()

        assertEquals(PairingOfferStatus.OPEN, response.status)
        val offered = assertNotNull(response.offer)
        assertEquals(window.hostName, offered.hostName)
        assertEquals(HostShortId.of(FINGERPRINT), offered.hostShortId)
        assertEquals(window.pairingToken, offered.pairingToken)
        assertEquals(window.expiresAtEpochMillis, offered.expiresAtEpochMillis)
    }

    @Test
    fun `reports closed again once the window is closed`() {
        host.openPairingWindow()
        host.pairingWindow.close()

        assertEquals(PairingOfferStatus.CLOSED, offer().status)
    }

    @Test
    fun `a window does not outlive the session it belongs to`() {
        val window = host.openPairingWindow()

        host.stopSession(window.pairingSessionId, "test")

        // Otherwise the host keeps advertising an offer whose token can no longer reach the claim step.
        assertEquals(PairingOfferStatus.CLOSED, offer().status)
    }

    @Test
    fun `reports blocked under the discovery lockout without handing out a token`() {
        host.openPairingWindow()
        blockedMessage = "Too many attempts."

        val response = offer()

        assertEquals(PairingOfferStatus.BLOCKED, response.status)
        assertNull(response.offer)
    }

    @Test
    fun `remote offer answers closed even while a window is open`() {
        host.openPairingWindow()

        val response = offer(ConnectionOrigin.REMOTE)

        assertEquals(PairingOfferStatus.CLOSED, response.status)
        assertNull(response.offer)
    }

    private fun offer(origin: ConnectionOrigin = ConnectionOrigin.LOCAL): OfferResponse =
        LinkJson.decodeFromString(
            OfferResponse.serializer(),
            routes.handle(DiscoverRequest.Offer, remoteAddress = "192.0.2.10", origin = origin),
        )

    /** One project, whose pairing window is the only thing an offer reads. */
    private inner class TestProject : DiscoveryProject {
        override val projectId: String = "project"
        override val host: SessionHost get() = this@PairingOfferRouteTest.host

        override fun isDiscoveryEnabled(): Boolean = true

        override fun isDiscoveryAvailable(): Boolean = true

        override fun discoveryBlockedMessage(): String? = blockedMessage

        override fun discoveryProjectChoices(): List<ProjectChoice> = emptyList()

        override fun discoveryDescriptor(): KnownHostDescriptor =
            KnownHostDescriptor(
                companionId = "companion",
                companionLabel = "VS Code",
                projectName = "Tennyo",
                hostVersion = "1.0",
                lastUsedAtEpochMillis = 0,
            )

        override fun discoveryEndpoints(): HostEndpoints =
            HostEndpoints("https://192.0.2.1:8443", emptyList(), FINGERPRINT, port = 8443)

        override fun recordDiscoverySecurityFailure(message: String, attackerSource: String?): Boolean = false
    }

    private inner class TestDirectory : ProjectDirectory {
        private val project = TestProject()

        override fun discoveryOwner(companionId: String): DiscoveryProject = project

        override fun pairingWindowOwner(): DiscoveryProject? =
            project.takeIf { it.host.pairingWindow.current() != null }

        override fun project(projectId: String): DiscoveryProject = project

        override fun message(message: DiscoveryMessage): String = message.name
    }

    private companion object {
        /** What [FakeHostPlatform]'s listener lease reports, so the derived short id matches the session's. */
        const val FINGERPRINT = "fingerprint"
    }
}
