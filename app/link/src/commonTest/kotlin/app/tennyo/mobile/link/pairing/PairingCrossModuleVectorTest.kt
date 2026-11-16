package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints
import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Client half of the cross-module lock on the pairing transcript: the host's `LinkSecurityTest` asserts the
 * same literal. The construction itself is shared in `:shared:crypto`; this pins how the app wires its attempt into it.
 */
class PairingCrossModuleVectorTest {

    @Test
    fun `pairing transcript matches the companion vector`() {
        val transcript = PairingAttempt(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = "device-public-key",
            deviceFingerprint = "device-fingerprint",
            attemptId = "attempt-1",
            serverChallenge = "server-challenge",
            clientEphemeralPublicKey = "client-ephemeral",
            companionEphemeralPublicKey = "companion-ephemeral",
        ).transcript()

        assertEquals(
            "tennyo-pairing-connect\n2\nsession\n" +
                    "PEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A\nfingerprint\n" +
                    "/api/tennyo/link/pair\ndevice-public-key\ndevice-fingerprint\nattempt-1\n" +
                    "server-challenge\ncompanion-ephemeral\nclient-ephemeral",
            transcript,
        )
    }

    private companion object {
        val ARTIFACT = LinkTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
        )
    }
}
