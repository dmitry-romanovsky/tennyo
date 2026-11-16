package app.tennyo.crypto

import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.ProtocolVersions
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Frozen outputs for every security construction. A change here re-keys pairing, remembered pairs, device proofs,
 * or call MACs for every installed client, so any diff must come with a
 * [ProtocolVersions.SECURITY_VERSION] bump.
 *
 * The app and companion keep their own copies of these vectors; those catch a consumer wiring the wrong argument
 * into an otherwise unchanged transcript.
 */
class SecurityTranscriptVectorTest {
    private val derivations = SecurityDerivations

    @Test
    fun deviceProofTranscript() {
        assertEquals(
            "tennyo-device-proof\n2\n/api/tennyo/link/pair\nRBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o\nsession\n" +
                    "PEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A\nfingerprint\ndevice-public-key\nnonce-1\n1700000000000",
            SecurityTranscripts.deviceProof(
                endpoint = LinkEndpoints.PAIR,
                requestHash = sha256Url("{}"),
                sessionId = "session",
                sessionTokenHash = sha256Url("token"),
                companionFingerprint = "fingerprint",
                devicePublicKey = "device-public-key",
                nonce = "nonce-1",
                signedAtEpochMillis = 1_700_000_000_000,
            ),
        )
    }

    @Test
    fun pairingConnectTranscript() {
        assertEquals(
            "tennyo-pairing-connect\n2\nsession\nPEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A\nfingerprint\n" +
                    "/api/tennyo/link/pair\ndevice-public-key\ndevice-fingerprint\nattempt-1\n" +
                    "server-challenge\ncompanion-ephemeral\nclient-ephemeral",
            SecurityTranscripts.pairingConnect(
                sessionId = "session",
                sessionTokenHash = sha256Url("token"),
                companionFingerprint = "fingerprint",
                route = LinkEndpoints.PAIR,
                devicePublicKey = "device-public-key",
                deviceFingerprint = "device-fingerprint",
                attemptId = "attempt-1",
                serverChallenge = "server-challenge",
                companionEphemeralPublicKey = "companion-ephemeral",
                clientEphemeralPublicKey = "client-ephemeral",
            ),
        )
    }

    @Test
    fun rememberedPairResumeTranscriptAndProof() {
        val transcript = SecurityTranscripts.rememberedPairResume(
            route = LinkEndpoints.PAIR,
            challengeId = "challenge-1",
            serverChallenge = "server-challenge-1",
            sessionId = "session-id",
            sessionTokenHash = sha256Url("session-token"),
            companionFingerprint = "transport-fingerprint",
            deviceFingerprint = "device-fingerprint",
            attemptId = "attempt-1",
            companionEphemeralPublicKey = "companion-ephemeral",
            clientEphemeralPublicKey = "client-ephemeral",
        )

        assertEquals(
            "tennyo-remembered-pair-resume\n2\n/api/tennyo/link/pair\nchallenge-1\nserver-challenge-1\nsession-id\n" +
                    "wQHpEUaclpFxBAtQ1wVDMTz5aP3vW6zHgHdvj7OZqzY\ntransport-fingerprint\ndevice-fingerprint\nattempt-1\n" +
                    "companion-ephemeral\nclient-ephemeral",
            transcript,
        )
        assertEquals("24TSq2Ujkg0BXTRiLATSSvHckhOb2Jkf97UdchtcDOw", hmacUrl("pair-secret", transcript))
    }

    @Test
    fun discoveryConnectTranscriptAndProof() {
        val transcript = SecurityTranscripts.discoveryConnect(
            route = LinkEndpoints.DISCOVER,
            companionId = "companion-1",
            companionFingerprint = "tls-fingerprint",
            deviceFingerprint = "device-fingerprint",
            challengeId = "challenge-1",
            serverChallenge = "server-challenge-1",
            attemptId = "attempt-1",
            companionEphemeralPublicKey = "companion-ephemeral",
            clientEphemeralPublicKey = "client-ephemeral",
        )

        assertEquals(
            "tennyo-discovery-connect\n2\n/api/tennyo/link/discover\ncompanion-1\ntls-fingerprint\ndevice-fingerprint\n" +
                    "challenge-1\nserver-challenge-1\nattempt-1\ncompanion-ephemeral\nclient-ephemeral",
            transcript,
        )
        assertEquals("-E8HAHX8sDGuL0sNdIcnAwaitpXeVhc4t7rhemIzmKY", hmacUrl("pair-secret", transcript))
    }

    @Test
    fun callMacKeyTranscriptAndMac() {
        val commandKey = derivations.commandMacKey(
            commandKeySeed = "command-key-seed".encodeToByteArray(),
            sessionToken = "token",
            streamId = "stream-1",
            approvedDeviceFingerprint = "device-fingerprint",
        )
        val transcript = SecurityTranscripts.callMac(
            sessionId = "session",
            sessionTokenHash = sha256Url("token"),
            companionFingerprint = "fingerprint",
            approvedDeviceFingerprint = "device-fingerprint",
            streamId = "stream-1",
            sequence = 7,
            callId = 3,
            callJsonHash = sha256Url("""{"op":"terminalInput","terminalId":"terminal-1","text":"ls"}"""),
        )

        assertEquals("0rl3RqT4Lf4naNtoy9eesKxYos4JklMo9y5TUraju6Q", commandKey.base64Url())
        assertEquals(
            "tennyo-link-call\n2\nsession\nPEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A\nfingerprint\n" +
                    "device-fingerprint\nstream-1\n7\n3\nodgO4-6FYbr17XnN7SBl7kyPujnuCGsTsK5edWV-gsk",
            transcript,
        )
        assertEquals(
            "f7IbyEmQ8VCMV7I-HzxzSHWsS7EudsJ7kXR4PvbOjj4",
            transcript.encodeToByteArray().toByteString().hmacSha256(commandKey.toByteString())
                .toByteArray().base64Url(),
        )
    }

    @Test
    fun pairingSelectionSecret() {
        val secret = derivations.pairingSelectionSecret(
            gridSeed = "grid-seed-1",
            selectedWords = listOf("Zephyr", "Cedar", "Maple", "Anchor"),
        )

        assertEquals("2q7nex__zAfbCcTMqMdB2smiYtc6dbZsxT_JVyqf2j0", secret.toByteString().sha256().toByteArray().base64Url())
    }

    @Test
    fun pairMaterialKey() {
        val key = derivations.pairMaterialKey(
            sharedSecret = "shared-secret".encodeToByteArray(),
            serverChallenge = "server-challenge",
            transcriptHash = "transcript-hash",
        )

        assertEquals("fgpxPSJwrcgGQsEjSa_Shne8ABKyMMA_bC4-Mj9am_0", key.base64Url())
    }

    @Test
    fun protectedPayloadKeys() {
        val keys = derivations.protectedPayloadKeys("response-key-0123456789abcdef012".encodeToByteArray())

        assertEquals("E5Pvjwf0oWGljIUCPXS8pw46-3TzeGbLJUPf6MuPWL8", keys.encryptionKey.base64Url())
        assertEquals("8Uq9QGtNFfqyXwyBJkHhtFLUaFb9Cs7ktp6Wnsxtc4A", keys.macKey.base64Url())
        assertEquals("0sDRl61s5EkzID0aQ8-P_g", derivations.protectedPayloadIv(keys.encryptionKey, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY").base64Url())
    }

    private fun sha256Url(value: String): String = value.encodeToByteArray().toByteString().sha256().toByteArray().base64Url()

    private fun hmacUrl(key: String, message: String): String =
        message.encodeToByteArray().toByteString().hmacSha256(key.encodeToByteArray().toByteString()).toByteArray().base64Url()

    private fun ByteArray.base64Url(): String = toByteString().base64Url().trimEnd('=')

}
