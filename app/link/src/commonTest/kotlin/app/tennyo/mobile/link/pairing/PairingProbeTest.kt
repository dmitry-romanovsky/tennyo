package app.tennyo.mobile.link.pairing

import app.tennyo.contract.OfferResponse
import app.tennyo.contract.PairingOffer
import app.tennyo.contract.PairingOfferStatus
import app.tennyo.contract.ProtocolVersions
import app.tennyo.crypto.HostShortId
import app.tennyo.mobile.link.transport.LinkHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the phone will and will not treat as a host it can pair with.
 *
 * The probe is the one place the app accepts a certificate it has never seen, so what it refuses matters as much
 * as what it accepts: an offer whose short id is not the one the observed certificate derives is not a host this
 * device can pair with, whatever it claims to be called.
 */
class PairingProbeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val pinned = mutableListOf<Pair<String, String>>()

    @Test
    fun `an open offer becomes a target carrying the observed fingerprint`() = runTest {
        val outcome = probe(offerResponse(PairingOfferStatus.OPEN, offer(HostShortId.of(FINGERPRINT)))).probe(SERVICE)

        val target = assertIs<ProbeOutcome.Found>(outcome).target
        assertEquals("Amber Harbour", target.hostName)
        assertEquals(FINGERPRINT, target.fingerprint)
        assertEquals(HostShortId.of(FINGERPRINT), target.hostShortId)
        assertEquals("https://192.0.2.10:8443", target.endpoint)
    }

    @Test
    fun `the ticket a target hands out is marked as a pairing window's credential`() = runTest {
        val outcome = probe(offerResponse(PairingOfferStatus.OPEN, offer(HostShortId.of(FINGERPRINT)))).probe(SERVICE)

        // Without the mark the connector would open with a hello, which a pairing token is refused by on purpose.
        val ticket = assertIs<ProbeOutcome.Found>(outcome).target.toProvisionalTicket()
        assertTrue(ticket.isPairingWindow)
        assertEquals("pairing-token", ticket.token)
    }

    @Test
    fun `the observed certificate is pinned before the offer is asked for`() = runTest {
        probe(offerResponse(PairingOfferStatus.OPEN, offer(HostShortId.of(FINGERPRINT)))).probe(SERVICE)

        assertEquals(listOf("https://192.0.2.10:8443" to FINGERPRINT), pinned)
    }

    @Test
    fun `an offer whose short id is not the certificate's is refused`() = runTest {
        val outcome = probe(offerResponse(PairingOfferStatus.OPEN, offer("ffffffffffff"))).probe(SERVICE)

        assertEquals(ProbeOutcome.Unreachable, outcome)
    }

    @Test
    fun `a host that is not in pairing mode reports closed`() = runTest {
        val outcome = probe(offerResponse(PairingOfferStatus.CLOSED)).probe(SERVICE)

        assertEquals(ProbeOutcome.Closed, outcome)
    }

    @Test
    fun `a host refusing this device under its lockout reports blocked`() = runTest {
        val outcome = probe(offerResponse(PairingOfferStatus.BLOCKED)).probe(SERVICE)

        assertEquals(ProbeOutcome.Blocked, outcome)
    }

    @Test
    fun `a host that presents no usable certificate is unreachable and is never asked for an offer`() = runTest {
        var asked = false
        val probe = PairingProbe(
            http = http { asked = true; offerResponse(PairingOfferStatus.OPEN, offer(HostShortId.of(FINGERPRINT))) },
            observeFingerprint = { null },
            pinFingerprint = { endpoint, fingerprint -> pinned += endpoint to fingerprint },
        )

        assertEquals(ProbeOutcome.Unreachable, probe.probe(SERVICE))
        assertTrue(!asked, "a host with no usable certificate must not be asked for an offer")
        assertTrue(pinned.isEmpty(), "nothing observed, so nothing to pin")
    }

    @Test
    fun `a host that does not answer is unreachable rather than failing the pass`() = runTest {
        val probe = PairingProbe(
            http = LinkHttp(
                httpClient = HttpClient(MockEngine) {
                    engine { addHandler { throw IllegalStateException("host unreachable") } }
                },
                deviceIdentity = DeviceIdentity(),
            ),
            observeFingerprint = { FINGERPRINT },
            pinFingerprint = { endpoint, fingerprint -> pinned += endpoint to fingerprint },
        )

        assertEquals(ProbeOutcome.Unreachable, probe.probe(SERVICE))
    }

    private fun probe(response: OfferResponse): PairingProbe =
        PairingProbe(
            http = http { response },
            observeFingerprint = { FINGERPRINT },
            pinFingerprint = { endpoint, fingerprint -> pinned += endpoint to fingerprint },
        )

    private fun http(response: () -> OfferResponse): LinkHttp =
        LinkHttp(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = json.encodeToString(OfferResponse.serializer(), response()),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            },
            deviceIdentity = DeviceIdentity(),
        )

    private fun offerResponse(status: PairingOfferStatus, offer: PairingOffer? = null) =
        OfferResponse(status = status, offer = offer)

    private fun offer(shortId: String) = PairingOffer(
        pairingSessionId = "session-1",
        pairingToken = "pairing-token",
        hostName = "Amber Harbour",
        hostShortId = shortId,
        hostLabel = "VS Code 1.107",
        expiresAtEpochMillis = 1_700_000_000_000L,
        securityVersion = ProtocolVersions.SECURITY_VERSION,
    )

    private companion object {
        const val FINGERPRINT = "tYRnHkuT6CF0VoVeJ6oVUmR6KpHPRmGSk8Lp0iGdGio"

        val SERVICE = LocalNetworkServiceAdvertisement(
            serviceType = "_tennyo-link._tcp",
            serviceInstanceId = "companion-1",
            host = "192.0.2.10",
            port = 8443,
        )
    }
}
