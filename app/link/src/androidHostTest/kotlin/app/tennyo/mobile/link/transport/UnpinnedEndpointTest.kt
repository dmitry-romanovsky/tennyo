package app.tennyo.mobile.link.transport

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.DiscoveryLookup
import app.tennyo.contract.PairRequest
import app.tennyo.contract.SessionClaimRequest
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnpinnedEndpointTest {
    private var sockets = 0
    private val client = HttpClient(MockEngine) {
        engine { addHandler { sockets += 1; error("no request may reach the network") } }
    }
    private val http = LinkHttp(client, DeviceIdentity())

    @Test
    fun `an endpoint whose host cannot be pinned is refused before any request`() = runBlocking {
        val ticket = LinkTicket(
            endpoint = "https://my_host.example:63343/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Tennyo",
            projectPath = "/project",
        )

        val hello = runCatching { http.hello(ticket) }.exceptionOrNull()
        val discover = runCatching {
            http.discover(ticket, DiscoverRequest.Discover(DiscoveryLookup("companion", "pair"))) { it }
        }.exceptionOrNull()
        val pair = runCatching { http.pair(ticket, PairRequest.Claim(SessionClaimRequest("session", "phone", "1")), { it }) }.exceptionOrNull()
        val channel = runCatching {
            LinkChannels(client, DeviceIdentity()).run(ticket, null, onOpened = {}, onPush = {})
        }.exceptionOrNull()

        listOf(hello, discover, pair, channel).forEach { fault ->
            assertTrue(fault is LinkFault.Unreachable)
            assertEquals(LinkFault.UnreachableReason.UnpinnedAddress, (fault as LinkFault.Unreachable).reason)
        }
        assertEquals(0, sockets)
    }

    @Test
    fun `a blank fingerprint is refused even when the address still holds an older pin`() = runBlocking {
        val endpoint = "https://198.51.100.7:63343/api/tennyo"
        val pinned = LinkTicket(endpoint, "session", "token", "known-fingerprint", "Tennyo", "/project")
        rememberTransportPin(pinned)

        val fault = runCatching { http.hello(pinned.copy(fingerprint = "")) }.exceptionOrNull()

        assertEquals(LinkFault.UnreachableReason.UnpinnedAddress, (fault as LinkFault.Unreachable).reason)
        assertEquals(0, sockets)
    }
}
