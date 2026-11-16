package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the device-proof transcript to an exact string.
 *
 * The host asserts the **same literal** in `LinkSecurityTest.device proof transcript is stable across modules`. Both
 * sides build the transcript through :shared:crypto and nothing else compares them; when the two drifted apart, every
 * signed request failed and no test noticed.
 */
class DeviceProofTranscriptTest {
    @Test
    fun deviceProofTranscriptIsStableAcrossModules() {
        val ticket = LinkTicket(
            endpoint = "https://192.168.1.10:63342",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
        )

        val transcript = deviceProofMessage(
            ticket = ticket,
            endpoint = LinkEndpoints.PAIR,
            requestText = "{}",
            devicePublicKey = "device-public-key",
            nonce = "nonce-1",
            signedAtEpochMillis = 1_700_000_000_000,
        ).decodeToString()

        assertEquals(
            listOf(
                "tennyo-device-proof",
                "2",
                "/api/tennyo/link/pair",
                "RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o",
                "session",
                "PEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A",
                "fingerprint",
                "device-public-key",
                "nonce-1",
                "1700000000000",
            ).joinToString("\n"),
            transcript,
        )
    }
}
