package app.tennyo.host.engine.session

import app.tennyo.host.engine.transport.OverlayNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveEndpointsTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val platform = FakeHostPlatform()
    private val host = SessionHost(testPanes(), platform, scope)
    private val lan = "https://192.168.1.10:63342/api/tennyo"
    private val overlayUrl = "https://100.101.2.3:63342/api/tennyo"

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `a session ticket carries overlay endpoints as alternates`() {
        platform.endpoints = HostEndpoints(
            primary = lan,
            alternates = listOf("https://192.168.1.11:63342/api/tennyo"),
            transportFingerprint = "fingerprint",
            port = 63342,
            overlay = listOf(OverlayEndpoint(OverlayNetwork.TAILNET_RANGE, "100.101.2.3", overlayUrl)),
        )

        val ticket = host.startSession()

        assertEquals(lan, ticket.endpoint)
        assertEquals(listOf("https://192.168.1.11:63342/api/tennyo", overlayUrl), ticket.alternateEndpoints)
    }

    @Test
    fun `the ticket a session serves follows the listener's addresses as they change`() {
        val session = checkNotNull(host.session(host.startSession().sessionId))
        assertEquals(emptyList(), session.currentTicket().alternateEndpoints)

        platform.endpoints = platform.endpoints.copy(
            overlay = listOf(OverlayEndpoint(OverlayNetwork.TAILNET_RANGE, "100.101.2.3", overlayUrl)),
        )

        assertEquals(listOf(overlayUrl), session.currentTicket().alternateEndpoints)
        assertEquals(session.artifact.token, session.currentTicket().token)
    }
}
