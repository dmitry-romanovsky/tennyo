package app.tennyo.host.engine.auth

import app.tennyo.contract.Capability
import app.tennyo.contract.DeviceProof
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.contract.ProtocolVersions
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.sha256
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.discovery.DiscoveryChallengeStore
import app.tennyo.host.engine.pairing.DiscoveryAttempt
import app.tennyo.host.engine.pairing.InMemoryPairStore
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_TTL_MILLIS
import app.tennyo.host.engine.pairing.PAIRING_WORDS
import app.tennyo.host.engine.pairing.PairingConfirmationState
import app.tennyo.host.engine.pairing.PairingSecret
import app.tennyo.host.engine.pairing.PairingVerifier
import app.tennyo.host.engine.pairing.RememberedPairAttempt
import app.tennyo.host.engine.pairing.RememberedPairMaterial
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.discoveryPairProof
import app.tennyo.host.engine.pairing.discoveryProofMatches
import app.tennyo.host.engine.pairing.newEphemeralKeyPair
import app.tennyo.host.engine.pairing.newPairingChallenge
import app.tennyo.host.engine.pairing.newPairingSecret
import app.tennyo.host.engine.pairing.pairingProof
import app.tennyo.host.engine.pairing.pairingTranscript
import app.tennyo.host.engine.pairing.protectPairMaterial
import app.tennyo.host.engine.pairing.rememberedPairProof
import app.tennyo.host.engine.pairing.rememberedPairProofMatches
import app.tennyo.host.engine.pairing.unprotectPairMaterial
import app.tennyo.host.engine.platform.HostTestSigner
import app.tennyo.host.engine.platform.HostTestSigningKey
import app.tennyo.host.engine.session.SessionTicket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkSecurityTest {

    @Test
    fun `pairing primitives are stable across modules`() {
        // Companion half of the cross-module lock. PairingCrossModuleVectorTest asserts the same three literals
        // from the client's independent implementations. Only the remembered-pair proof had such a vector before, and
        // the device transcript drifted unnoticed as a result.
        val secret = PairingSecret(
            words = listOf("Zephyr", "Cedar", "Maple", "Anchor"),
            wordGrid = VECTOR_GRID,
            gridSeed = "grid-seed-1",
        )

        assertEquals(
            "2q7nex__zAfbCcTMqMdB2smiYtc6dbZsxT_JVyqf2j0",
            sha256(secret.expectedSelectionSecret()).toBase64Url(),
        )
        assertEquals(
            "tennyo-pairing-connect\n${ProtocolVersions.SECURITY_VERSION}\nsession\n" +
                    "PEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A\nfingerprint\n" +
                    "/api/tennyo/link/pair\ndevice-public-key\ndevice-fingerprint\nattempt-1\n" +
                    "server-challenge\ncompanion-ephemeral\nclient-ephemeral",
            pairingTranscript(
                artifact = ARTIFACT,
                route = LinkEndpoints.PAIR,
                devicePublicKey = "device-public-key",
                deviceFingerprint = "device-fingerprint",
                attemptId = "attempt-1",
                serverChallenge = "server-challenge",
                clientEphemeralPublicKey = "client-ephemeral",
                companionEphemeralPublicKey = "companion-ephemeral",
            ),
        )
        assertEquals(
            "fgpxPSJwrcgGQsEjSa_Shne8ABKyMMA_bC4-Mj9am_0",
            SecurityDerivations.pairMaterialKey(
                sharedSecret = "shared-secret".encodeToByteArray(),
                serverChallenge = "server-challenge",
                transcriptHash = "transcript-hash",
            ).toBase64Url(),
        )
    }

    @Test
    fun `device proof transcript is stable across modules`() {
        // The app asserts this same literal in DeviceSignatureTranscriptTest. Both sides call SecurityTranscripts, so
        // this pins the construction itself: a changed line re-derives every proof and must be deliberate.
        val proof = DeviceProof(
            devicePublicKey = "device-public-key",
            deviceFingerprint = "device-fingerprint",
            nonce = "nonce-1",
            signedAtEpochMillis = 1_700_000_000_000,
            signature = "",
        )

        assertEquals(
            listOf(
                "tennyo-device-proof",
                "${ProtocolVersions.SECURITY_VERSION}",
                "/api/tennyo/link/pair",
                "RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o",
                "session",
                "PEaenWxYddN6Q_NT1PiOYfz4EsZu7jRXRlpAsNpBU-A",
                "fingerprint",
                "device-public-key",
                "nonce-1",
                "1700000000000",
            ).joinToString("\n"),
            deviceProofMessage(ARTIFACT, LinkEndpoints.PAIR, "{}", proof).decodeToString(),
        )
    }

    @Test
    fun `pairing confirmation challenge expires after two minutes`() {
        val issuedAt = 1_700_000_000_000L
        val challenge = newPairingChallenge(verifier = pairingVerifier(), nowEpochMillis = issuedAt)

        assertEquals(2 * 60 * 1_000L, PAIRING_CONFIRMATION_TTL_MILLIS)
        assertEquals(issuedAt + PAIRING_CONFIRMATION_TTL_MILLIS, challenge.expiresAtEpochMillis)
    }

    @Test
    fun `device proof verifies only for the endpoint and request text it signed`() {
        val key = keyPair()
        val requestText = """{"op":"claim","claim":{"sessionId":"session"}}"""
        val proof = key.proof(LinkEndpoints.PAIR, requestText, nonce = "nonce-1")

        assertTrue(verifyDeviceSignature(ARTIFACT, LinkEndpoints.PAIR, requestText, proof))
        assertFalse(verifyDeviceSignature(ARTIFACT, LinkEndpoints.CHANNEL, requestText, proof))
        assertFalse(verifyDeviceSignature(ARTIFACT, LinkEndpoints.PAIR, requestText.replace("session", "other"), proof))
        assertFalse(verifyDeviceSignature(ARTIFACT.copy(token = "other-token"), LinkEndpoints.PAIR, requestText, proof))
        assertFalse(verifyDeviceSignature(ARTIFACT, LinkEndpoints.PAIR, requestText, proof.copy(nonce = "nonce-2")))
    }

    @Test
    fun `device proof is accepted once while fresh`() {
        val key = keyPair()
        val store = NonceStore()
        val proof = key.proof(LinkEndpoints.CHANNEL, "", nonce = "nonce-1")

        assertNull(store.verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", proof, PROOF_SIGNED_AT))
        assertEquals(
            LinkFaultCode.REPLAYED,
            store.verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", proof, PROOF_SIGNED_AT + 1),
        )
    }

    @Test
    fun `device proof outside the freshness window is refused as a replay`() {
        val key = keyPair()
        val late = PROOF_SIGNED_AT + LinkLimits.PROOF_FRESHNESS_MILLIS + 1
        val early = PROOF_SIGNED_AT - LinkLimits.PROOF_FRESHNESS_MILLIS - 1

        assertEquals(
            LinkFaultCode.REPLAYED,
            NonceStore().verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", key.proof(LinkEndpoints.CHANNEL, ""), late),
        )
        assertEquals(
            LinkFaultCode.REPLAYED,
            NonceStore().verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", key.proof(LinkEndpoints.CHANNEL, ""), early),
        )
    }

    @Test
    fun `device proof with a forged fingerprint, a missing signature or an old version is refused`() {
        val key = keyPair()
        val proof = key.proof(LinkEndpoints.CHANNEL, "")

        assertEquals(
            LinkFaultCode.UNAUTHORIZED,
            NonceStore().verifyDeviceProof(
                ARTIFACT, LinkEndpoints.CHANNEL, "", proof.copy(deviceFingerprint = deviceKeyHash("other")), PROOF_SIGNED_AT,
            ),
        )
        assertEquals(
            LinkFaultCode.UNAUTHORIZED,
            NonceStore().verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", proof.copy(signature = ""), PROOF_SIGNED_AT),
        )
        assertEquals(
            LinkFaultCode.PROTOCOL_VERSION_UNSUPPORTED,
            NonceStore().verifyDeviceProof(
                ARTIFACT, LinkEndpoints.CHANNEL, "", proof.copy(securityVersion = ProtocolVersions.SECURITY_VERSION - 1),
                PROOF_SIGNED_AT,
            ),
        )
    }

    @Test
    fun `a refused signature does not burn the proof nonce`() {
        val key = keyPair()
        val store = NonceStore()
        val proof = key.proof(LinkEndpoints.CHANNEL, "")

        assertEquals(
            LinkFaultCode.UNAUTHORIZED,
            store.verifyDeviceProof(ARTIFACT, LinkEndpoints.PAIR, "", proof, PROOF_SIGNED_AT),
        )
        assertNull(store.verifyDeviceProof(ARTIFACT, LinkEndpoints.CHANNEL, "", proof, PROOF_SIGNED_AT))
    }

    @Test
    fun `pairing proof is bound to the route device attempt and server challenge`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val proof = devicePairingProof(
            SECRET_WORDS,
            publicKey,
            fingerprint,
            attemptId = "attempt-1",
            serverChallenge = "challenge-1",
        )
        val verifier = pairingVerifier()

        assertTrue(verifier.verifies(publicKey, fingerprint, "attempt-1", "challenge-1", proof))
        assertFalse(
            verifier.verifies(
                publicKey,
                fingerprint,
                "attempt-1",
                "challenge-1",
                proof,
                route = LinkEndpoints.HELLO,
            ),
        )
        assertFalse(verifier.verifies(publicKey, fingerprint, "attempt-2", "challenge-1", proof))
        assertFalse(verifier.verifies(publicKey, fingerprint, "attempt-1", "challenge-2", proof))
    }

    @Test
    fun `pairing proof rejects the correct words in the wrong order`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val reversedProof = devicePairingProof(
            SECRET_WORDS.reversed(),
            publicKey,
            fingerprint,
            attemptId = "attempt-1",
            serverChallenge = "challenge-1",
        )

        // Order carries most of the entropy here: P(16,4) = 43,680 ordered versus C(16,4) = 1,820 unordered.
        assertFalse(pairingVerifier().verifies(publicKey, fingerprint, "attempt-1", "challenge-1", reversedProof))
    }

    @Test
    fun `pairing proof rejects a substituted client ephemeral key`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val proof = devicePairingProof(
            SECRET_WORDS,
            publicKey,
            fingerprint,
            attemptId = "attempt-1",
            serverChallenge = "challenge-1",
        )

        // Both ephemeral keys are inside the transcript; without that a relay could swap its own key in and the
        // exchange would authenticate nothing at all.
        assertFalse(
            pairingVerifier().verify(
                artifact = ARTIFACT,
                route = LinkEndpoints.PAIR,
                devicePublicKey = publicKey,
                deviceFingerprint = fingerprint,
                attemptId = "attempt-1",
                serverChallenge = "challenge-1",
                clientEphemeralPublicKey = newEphemeralKeyPair().publicKeyBase64,
                clientPairingProof = proof,
            ),
        )
    }

    @Test
    fun `pairing secret is four distinct words drawn from the shared word list`() {
        repeat(50) {
            val secret = newPairingSecret()

            assertEquals(4, secret.words.size)
            assertEquals(4, secret.words.toSet().size)
            assertTrue(secret.words.all { it in PAIRING_WORDS })
        }
    }

    @Test
    fun `pairing word grid is sixteen distinct sorted words containing the secret`() {
        repeat(50) {
            val secret = newPairingSecret()

            assertEquals(16, secret.wordGrid.size)
            assertEquals(16, secret.wordGrid.toSet().size)
            assertEquals(secret.wordGrid.sorted(), secret.wordGrid)
            assertTrue(secret.wordGrid.containsAll(secret.words))
        }
    }

    @Test
    fun `pairing secret order is not the sorted grid order`() {
        // Sorting the secret anywhere would make the tap order readable off the grid and silently cut the search
        // space 24-fold, with nothing else failing. False-failure odds here are 24^-200.
        val secrets = List(200) { newPairingSecret() }

        assertTrue(secrets.any { it.words != it.words.sorted() })
        assertTrue(secrets.any { it.wordGrid.take(4) != it.words })
    }

    @Test
    fun `pairing words are legible and prefix free`() {
        assertEquals(81, PAIRING_WORDS.size)
        assertEquals(PAIRING_WORDS.toSet().size, PAIRING_WORDS.size)
        assertTrue(PAIRING_WORDS.all { it.length in 4..7 })
        // Distinct two-letter prefixes keep confusable pairs apart and make the list prefix-free, which is what lets
        // tile keys concatenate unambiguously.
        assertEquals(
            PAIRING_WORDS.size,
            PAIRING_WORDS.map { it.take(2).uppercase() }.toSet().size,
        )
    }

    @Test
    fun `tile keys are derived from the word and the session seed`() {
        val other = PairingSecret(SECRET_WORDS, WORD_GRID, gridSeed = "grid-seed-2")

        assertContentEquals(
            PAIRING_SECRET.selectionSecret(SECRET_WORDS),
            PAIRING_SECRET.expectedSelectionSecret(),
        )
        assertFalse(
            PAIRING_SECRET.expectedSelectionSecret().contentEquals(other.expectedSelectionSecret()),
        )
        assertFalse(
            PAIRING_SECRET.selectionSecret(SECRET_WORDS)
                .contentEquals(PAIRING_SECRET.selectionSecret(SECRET_WORDS.reversed())),
        )
    }

    @Test
    fun `protected pair material does not expose the pair secret as plaintext`() {
        val responseKey = ByteArray(32) { index -> index.toByte() }
        val material = RememberedPairMaterial(
            pairId = "pair-1",
            pairSecret = "secret-value",
            expiresAtEpochMillis = 10_000L,
            deviceLabel = "Tennyo Android",
        )

        val protectedPayload = protectPairMaterial(material, responseKey)

        assertFalse(protectedPayload.contains(material.pairSecret))
        assertEquals(material, unprotectPairMaterial(protectedPayload, responseKey))
        assertNull(unprotectPairMaterial(protectedPayload.replace('a', 'b'), responseKey))
    }

    @Test
    fun `protected pair payload is stable across modules`() {
        // The same material and key as the frozen ciphertext in the crypto module's LinkConstructionsTest.
        val countingNonce = { size: Int -> ByteArray(size) { index -> (index + 1).toByte() } }

        assertEquals(
            """{"algorithm":"aes-256-ctr+hmac-sha256","nonce":"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY",""" +
                    """"ciphertext":"xA-sw_ZJjfu9tJtjt2KqVILZOqhz95jUYalt4xq3ZJXzxz1HxZ2kGEXEu9oCzuO1AM1cb8P8TcPaBpUK1ufwst6KZKQJ""" +
                    """8KF8leva3rpwzBZRMgrZfPH9wU_KCJGqmnRsFllxQSdBAAJlL1zk6smrrB6JsFFTYAuo4Q-znJCXoKiiowk0Gjf3fBa8vIRpfHZMBTM",""" +
                    """"mac":"2-5PHxxXIjrpIlLWVimHcx9FufkZE0SttFnY8zqCfJ4"}""",
            protectPairMaterial(
                material = RememberedPairMaterial(
                    pairId = "pair-1",
                    pairSecret = "pair-secret",
                    expiresAtEpochMillis = 1_800_000_000_000,
                    deviceLabel = "Pixel 9 Android 17",
                    companionId = "companion-1",
                ),
                responseKey = "response-key-0123456789abcdef012".encodeToByteArray(),
                randomBytes = countingNonce,
            ),
        )
    }

    @Test
    fun `remembered pair proof is bound to challenge route session attempt and both ephemeral keys`() {
        val record = rememberedPairRecord()
        val deviceFingerprint = record.deviceKeyHash.orEmpty()
        val attempt = resumeAttempt(deviceFingerprint)
        val proof = rememberedPairProof(record.pairSecret, attempt)

        assertTrue(rememberedPairProofMatches(record, attempt, proof))
        assertFalse(
            rememberedPairProofMatches(
                record,
                attempt.copy(route = LinkEndpoints.DISCOVER),
                proof,
            ),
        )
        assertFalse(
            rememberedPairProofMatches(
                record,
                attempt.copy(deviceFingerprint = "other-device-fingerprint"),
                proof,
            ),
        )
        assertFalse(rememberedPairProofMatches(record, attempt.copy(attemptId = "attempt-2"), proof))
        // Without the ephemeral keys in the transcript a relay could swap its own key in and the ECDH exchange that
        // carries the rotated pair material would authenticate nothing.
        assertFalse(
            rememberedPairProofMatches(
                record,
                attempt.copy(clientEphemeralPublicKey = "other-client-ephemeral"),
                proof,
            ),
        )
        assertFalse(
            rememberedPairProofMatches(
                record,
                attempt.copy(companionEphemeralPublicKey = "other-companion-ephemeral"),
                proof,
            ),
        )
    }

    @Test
    fun `remembered pair proof has a stable device-bound transcript vector`() {
        assertEquals(
            "24TSq2Ujkg0BXTRiLATSSvHckhOb2Jkf97UdchtcDOw",
            rememberedPairProof(
                pairSecret = "pair-secret",
                attempt = RememberedPairAttempt(
                    artifact = rememberedPairVectorArtifact(),
                    route = LinkEndpoints.PAIR,
                    challengeId = "challenge-1",
                    attemptId = "attempt-1",
                    serverChallenge = "server-challenge-1",
                    deviceFingerprint = "device-fingerprint",
                    clientEphemeralPublicKey = "client-ephemeral",
                    companionEphemeralPublicKey = "companion-ephemeral",
                ),
            ),
        )
    }

    @Test
    fun `call mac key and mac are stable across modules`() {
        // Same literals as the app's CommandSignerTest.
        val commandKey = SecurityDerivations.commandMacKey(
            commandKeySeed = "command-key-seed".encodeToByteArray(),
            sessionToken = "token",
            streamId = "stream-1",
            approvedDeviceFingerprint = "device-fingerprint",
        )

        assertEquals("0rl3RqT4Lf4naNtoy9eesKxYos4JklMo9y5TUraju6Q", commandKey.toBase64Url())
        assertEquals(
            "f7IbyEmQ8VCMV7I-HzxzSHWsS7EudsJ7kXR4PvbOjj4",
            callMac(
                commandKey = commandKey,
                ticket = ARTIFACT,
                approvedDeviceFingerprint = "device-fingerprint",
                streamId = "stream-1",
                sequence = 7,
                callId = 3,
                call = LinkCall.TerminalInput(terminalId = "terminal-1", text = "ls"),
            ),
        )
    }

    @Test
    fun `discovery pair proof has a stable transcript vector`() {
        // Same literal as the app's RememberedPairProofTest.
        assertEquals(
            "-E8HAHX8sDGuL0sNdIcnAwaitpXeVhc4t7rhemIzmKY",
            discoveryPairProof(
                pairSecret = "pair-secret",
                attempt = DiscoveryAttempt(
                    companionId = "companion-1",
                    fingerprint = "tls-fingerprint",
                    route = LinkEndpoints.DISCOVER,
                    challengeId = "challenge-1",
                    attemptId = "attempt-1",
                    serverChallenge = "server-challenge-1",
                    deviceFingerprint = "device-fingerprint",
                    clientEphemeralPublicKey = "client-ephemeral",
                    companionEphemeralPublicKey = "companion-ephemeral",
                ),
            ),
        )
    }

    @Test
    fun `auto start pair proof is bound to companion fingerprint challenge attempt and ephemeral keys`() {
        val record = rememberedPairRecord()
        val attempt = DiscoveryAttempt(
            companionId = "companion-1",
            fingerprint = "tls-fingerprint",
            route = LinkEndpoints.DISCOVER,
            challengeId = "challenge-1",
            attemptId = "attempt-1",
            serverChallenge = "server-1",
            deviceFingerprint = record.deviceKeyHash.orEmpty(),
            clientEphemeralPublicKey = "client-ephemeral",
            companionEphemeralPublicKey = "companion-ephemeral",
        )
        val proof = discoveryPairProof(record.pairSecret, attempt)

        assertTrue(discoveryProofMatches(record, attempt, proof))
        assertFalse(
            discoveryProofMatches(record, attempt.copy(fingerprint = "other-fingerprint"), proof),
        )
        assertFalse(
            discoveryProofMatches(
                record,
                attempt.copy(clientEphemeralPublicKey = "other-client-ephemeral"),
                proof,
            ),
        )
    }

    @Test
    fun `rotated pair material key follows the key exchange rather than the pair secret`() {
        val attempt = resumeAttempt("device-fingerprint")
        val companion = newEphemeralKeyPair()
        val client = newEphemeralKeyPair()

        val key = companion.pairMaterialKey(
            clientEphemeralPublicKey = client.publicKeyBase64,
            serverChallenge = attempt.serverChallenge,
            transcriptHash = attempt.transcriptHash(),
        )
        val otherClientKey = companion.pairMaterialKey(
            clientEphemeralPublicKey = newEphemeralKeyPair().publicKeyBase64,
            serverChallenge = attempt.serverChallenge,
            transcriptHash = attempt.transcriptHash(),
        )

        // A leaked pairSecret must not unlock the payload key: it depends only on the exchange.
        assertNotNull(key)
        assertNotNull(otherClientKey)
        assertFalse(key!!.contentEquals(otherClientKey!!))
        assertNull(
            companion.pairMaterialKey(
                clientEphemeralPublicKey = "not-a-key",
                serverChallenge = attempt.serverChallenge,
                transcriptHash = attempt.transcriptHash(),
            ),
        )
    }

    @Test
    fun `a rotated pair's old secret works once more, in case the reply was lost, and then never again`() {
        val store = InMemoryPairStore()
        val record = rememberedPairRecord(pairId = "pair-old", pairSecret = "secret-old", deviceKeyHash = "device-key")
        val replacement =
            rememberedPairRecord(pairId = "pair-new", pairSecret = "secret-new", deviceKeyHash = "device-key")
        val retry = rememberedPairRecord(pairId = "pair-retry", pairSecret = "secret-retry", deviceKeyHash = "device-key")
        store.save(record)

        assertTrue(store.replace(record.pairId, record.pairSecret, replacement, nowEpochMillis = 2_000L))
        assertEquals(replacement, store.find(replacement.pairId, nowEpochMillis = 2_000L))
        assertEquals(1, store.summaries(nowEpochMillis = 2_000L).size)

        assertTrue(store.replace(record.pairId, record.pairSecret, retry, nowEpochMillis = 2_001L))
        assertNull(store.find(record.pairId, nowEpochMillis = 2_001L))
        assertNull(store.find(replacement.pairId, nowEpochMillis = 2_001L))
        assertFalse(store.replace(record.pairId, record.pairSecret, retry, nowEpochMillis = 2_002L))
    }

    @Test
    fun `a rotated pair's old secret stops working when the new pair is first rotated`() {
        val store = InMemoryPairStore()
        val record = rememberedPairRecord(pairId = "pair-old", pairSecret = "secret-old", deviceKeyHash = "device-key")
        val replacement =
            rememberedPairRecord(pairId = "pair-new", pairSecret = "secret-new", deviceKeyHash = "device-key")
        val next = rememberedPairRecord(pairId = "pair-next", pairSecret = "secret-next", deviceKeyHash = "device-key")
        store.save(record)
        store.replace(record.pairId, record.pairSecret, replacement, nowEpochMillis = 2_000L)

        assertTrue(store.replace(replacement.pairId, replacement.pairSecret, next, nowEpochMillis = 2_001L))

        assertNull(store.find(record.pairId, nowEpochMillis = 2_001L))
    }

    @Test
    fun `a rotated pair's old secret is dropped when its grace ends`() {
        val store = InMemoryPairStore()
        val longLived = 100_000L
        val record = rememberedPairRecord(pairId = "pair-old", pairSecret = "secret-old", deviceKeyHash = "device-key")
            .copy(expiresAtEpochMillis = longLived)
        val replacement =
            rememberedPairRecord(pairId = "pair-new", pairSecret = "secret-new", deviceKeyHash = "device-key")
                .copy(expiresAtEpochMillis = longLived)
        store.save(record)
        store.replace(record.pairId, record.pairSecret, replacement, nowEpochMillis = 2_000L)

        assertNull(store.find(record.pairId, nowEpochMillis = 2_000L + DiscoveryChallengeStore.CHALLENGE_TTL_MILLIS))
        assertEquals(replacement, store.find(replacement.pairId, nowEpochMillis = 2_001L))
    }

    @Test
    fun `remembered pair store replaces older records for the same device key`() {
        val store = InMemoryPairStore()
        store.save(rememberedPairRecord(pairId = "pair-old", deviceKeyHash = "device-key"))
        store.save(rememberedPairRecord(pairId = "pair-new", deviceKeyHash = "device-key"))

        assertNull(store.find("pair-old", nowEpochMillis = 2_000L))
        assertTrue(store.find("pair-new", nowEpochMillis = 2_000L) != null)
        assertEquals(1, store.summaries(nowEpochMillis = 2_000L).size)
    }

    @Test
    fun `pair resume challenge is single use`() {
        val state = pairingConfirmationState()

        val consumed = state.consumePairResumeChallenge(
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            nowEpochMillis = 1_000L,
        )
        val replay = state.consumePairResumeChallenge(
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            nowEpochMillis = 1_001L,
        )

        assertEquals(INITIAL_CHALLENGE, consumed)
        assertNull(replay)
    }

    @Test
    fun `pairing confirmation reports expired challenge separately from failed attempts`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val state = pairingConfirmationState()

        val attempt = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = devicePairingProof(
                SECRET_WORDS,
                publicKey,
                fingerprint,
                "attempt-1",
                INITIAL_CHALLENGE.serverChallenge,
            ),
            nowEpochMillis = 10_000L,
        )

        assertEquals(PairingConfirmationStatus.StoppedServerExceededMaximumAttempts, attempt.status)
        assertTrue(attempt.expired)
    }

    @Test
    fun `pairing confirmation rejects oversized proof without accepting the attempt`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val state = pairingConfirmationState()

        val attempt = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "x".repeat(257),
            nowEpochMillis = 1_000L,
        )

        assertEquals(PairingConfirmationStatus.IncorrectSelection, attempt.status)
        assertFalse(attempt.expired)
        assertTrue(attempt.pairingChallenge != null)
    }

    @Test
    fun `pairing confirmation allows the correct selection before the third failed attempt`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val state = pairingConfirmationState()

        val first = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "wrong",
            nowEpochMillis = 1_000L,
        )
        assertEquals(PairingConfirmationStatus.IncorrectSelection, first.status)

        val secondChallenge = first.pairingChallenge!!
        val second = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = secondChallenge.challengeId,
            attemptId = "attempt-2",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "wrong",
            nowEpochMillis = 1_001L,
        )
        assertEquals(PairingConfirmationStatus.IncorrectSelection, second.status)

        val finalChallenge = second.pairingChallenge!!
        val success = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = finalChallenge.challengeId,
            attemptId = "attempt-3",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = devicePairingProof(
                SECRET_WORDS,
                publicKey,
                fingerprint,
                "attempt-3",
                finalChallenge.serverChallenge
            ),
            nowEpochMillis = 1_002L,
        )

        assertEquals(PairingConfirmationStatus.Success, success.status)
    }

    @Test
    fun `pairing confirmation stops on the third failed attempt and rejects replayed attempt ids`() {
        val publicKey = keyPair().publicBase64()
        val fingerprint = deviceKeyHash(publicKey)
        val state = pairingConfirmationState()

        val first = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "wrong",
            nowEpochMillis = 1_000L,
        )
        val replay = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = INITIAL_CHALLENGE.challengeId,
            attemptId = "attempt-1",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = devicePairingProof(
                SECRET_WORDS,
                publicKey,
                fingerprint,
                "attempt-1",
                INITIAL_CHALLENGE.serverChallenge
            ),
            nowEpochMillis = 1_000L,
        )
        assertEquals(PairingConfirmationStatus.IncorrectSelection, replay.status)

        val second = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = first.pairingChallenge!!.challengeId,
            attemptId = "attempt-2",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "wrong",
            nowEpochMillis = 1_001L,
        )
        val third = state.evaluate(
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = publicKey,
            deviceFingerprint = fingerprint,
            challengeId = second.pairingChallenge!!.challengeId,
            attemptId = "attempt-3",
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = "wrong",
            nowEpochMillis = 1_002L,
        )

        assertEquals(PairingConfirmationStatus.StoppedServerExceededMaximumAttempts, third.status)
    }

    @Test
    fun `accepted nonces reject replay until they expire`() {
        val store = NonceStore(ttlMillis = 1_000L, maxEntries = 10)

        assertTrue(store.accept("device:nonce", nowEpochMillis = 10_000L))
        assertFalse(store.accept("device:nonce", nowEpochMillis = 10_100L))
        assertTrue(store.accept("device:other", nowEpochMillis = 11_001L))
        assertEquals(1, store.size())
        assertTrue(store.accept("device:nonce", nowEpochMillis = 11_002L))
    }

    @Test
    fun `accepted nonce store trims oldest entries`() {
        val store = NonceStore(ttlMillis = 60_000L, maxEntries = 2)

        assertTrue(store.accept("device:first", nowEpochMillis = 1L))
        assertTrue(store.accept("device:second", nowEpochMillis = 2L))
        assertTrue(store.accept("device:third", nowEpochMillis = 3L))

        assertEquals(2, store.size())
        assertTrue(store.accept("device:first", nowEpochMillis = 4L))
    }

    @Test
    fun `a MACed call verifies once on its stream`() {
        val stream = commandStream()
        val call = callFrame(sequence = 1L, call = LinkCall.TerminalInput("terminal-1", "ls"))

        assertNull(stream.verify(call))
        assertEquals(LinkFaultCode.REPLAYED, stream.verify(call))
        assertEquals(LinkFaultCode.REPLAYED, stream.verify(callFrame(sequence = 1L, call = LinkCall.Stats, id = 2L)))
    }

    @Test
    fun `a call whose body, id or sequence changed under a reused MAC is refused`() {
        val stream = commandStream()
        val signed = callFrame(sequence = 1L, call = LinkCall.TerminalInput("terminal-1", "ls"))

        assertEquals(
            LinkFaultCode.MAC_INVALID,
            stream.verify(signed.copy(call = LinkCall.TerminalInput("terminal-1", "rm -rf target"))),
        )
        assertEquals(LinkFaultCode.MAC_INVALID, stream.verify(signed.copy(id = 99L)))
        assertEquals(LinkFaultCode.MAC_INVALID, stream.verify(signed.copy(seq = 2L)))
        assertNull(stream.verify(signed))
    }

    @Test
    fun `a call MACed for another stream or another device is refused`() {
        val stream = commandStream()

        assertEquals(
            LinkFaultCode.MAC_INVALID,
            stream.verify(callFrame(sequence = 1L, call = LinkCall.Stats, streamId = "other-stream")),
        )
        assertEquals(
            LinkFaultCode.MAC_INVALID,
            stream.verify(callFrame(sequence = 1L, call = LinkCall.Stats, approvedDeviceFingerprint = "other-device")),
        )
    }

    private fun HostTestSigningKey.proof(
        endpoint: String,
        requestText: String,
        nonce: String = "nonce-${requestText.length}-${endpoint.length}",
    ): DeviceProof {
        val publicKey = publicBase64()
        val unsigned = DeviceProof(
            devicePublicKey = publicKey,
            deviceFingerprint = deviceKeyHash(publicKey),
            nonce = nonce,
            signedAtEpochMillis = PROOF_SIGNED_AT,
            signature = "",
        )
        val message = deviceProofMessage(ARTIFACT, endpoint, requestText, unsigned)
        return unsigned.copy(signature = HostTestSigner.sign(this, message).toBase64Url())
    }

    private fun HostTestSigningKey.publicBase64(): String = publicKeySpki.toBase64Url()

    private fun devicePairingProof(
        selection: List<String>,
        devicePublicKey: String,
        deviceFingerprint: String,
        attemptId: String,
        serverChallenge: String,
        clientEphemeralPublicKey: String = CLIENT_EPHEMERAL.publicKeyBase64,
        companionEphemeralPublicKey: String = COMPANION_EPHEMERAL.publicKeyBase64,
    ): String =
        pairingProof(
            selectionSecret = PAIRING_SECRET.selectionSecret(selection),
            artifact = ARTIFACT,
            route = LinkEndpoints.PAIR,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            serverChallenge = serverChallenge,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            companionEphemeralPublicKey = companionEphemeralPublicKey,
        )

    private fun pairingConfirmationState(): PairingConfirmationState =
        PairingConfirmationState(
            verifier = pairingVerifier(),
            initialChallenge = INITIAL_CHALLENGE,
            expiresAtEpochMillis = 10_000L,
        )

    private fun resumeAttempt(deviceFingerprint: String) = RememberedPairAttempt(
        artifact = ARTIFACT,
        route = LinkEndpoints.PAIR,
        challengeId = "challenge-1",
        attemptId = "attempt-1",
        serverChallenge = "server-challenge-1",
        deviceFingerprint = deviceFingerprint,
        clientEphemeralPublicKey = "client-ephemeral",
        companionEphemeralPublicKey = "companion-ephemeral",
    )

    private fun pairingVerifier(): PairingVerifier =
        PairingVerifier(secret = PAIRING_SECRET, ephemeralKeyPair = COMPANION_EPHEMERAL)

    /** Verification with the fixture defaults filled in, so call sites read as the thing under test. */
    private fun PairingVerifier.verifies(
        devicePublicKey: String,
        deviceFingerprint: String,
        attemptId: String,
        serverChallenge: String,
        proof: String,
        route: String = LinkEndpoints.PAIR,
    ): Boolean =
        verify(
            artifact = ARTIFACT,
            route = route,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            serverChallenge = serverChallenge,
            clientEphemeralPublicKey = CLIENT_EPHEMERAL.publicKeyBase64,
            clientPairingProof = proof,
        )

    private fun rememberedPairRecord(
        pairId: String = "pair-id",
        pairSecret: String = "pair-secret",
        deviceLabel: String = "Tennyo Android",
        deviceKeyHash: String = "device-key-$pairId",
    ): RememberedPairRecord =
        RememberedPairRecord(
            pairId = pairId,
            pairSecret = pairSecret,
            deviceLabel = deviceLabel,
            deviceKeyHash = deviceKeyHash,
            createdAtEpochMillis = 1_000L,
            lastConfirmedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 10_000L,
        )

    private fun rememberedPairVectorArtifact(): SessionTicket =
        SessionTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session-id",
            token = "session-token",
            fingerprint = "transport-fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
            capabilities = emptySet(),
        )

    private fun commandStream(): CommandStream =
        CommandStream(
            ticket = ARTIFACT,
            streamId = COMMAND_STREAM_ID,
            approvedDeviceFingerprint = COMMAND_DEVICE_FINGERPRINT,
            commandKeySeed = COMMAND_KEY_SEED,
        )

    private fun callFrame(
        sequence: Long,
        call: LinkCall,
        id: Long = sequence,
        streamId: String = COMMAND_STREAM_ID,
        approvedDeviceFingerprint: String = COMMAND_DEVICE_FINGERPRINT,
    ): LinkFrame.Call {
        val commandKey = SecurityDerivations.commandMacKey(
            commandKeySeed = COMMAND_KEY_SEED,
            sessionToken = ARTIFACT.token,
            streamId = streamId,
            approvedDeviceFingerprint = approvedDeviceFingerprint,
        )
        val mac = callMac(commandKey, ARTIFACT, approvedDeviceFingerprint, streamId, sequence, id, call)
        return LinkFrame.Call(id = id, seq = sequence, mac = mac, call = call)
    }

    private fun keyPair(): HostTestSigningKey = HostTestSigner.newKey()

    private companion object {
        const val PROOF_SIGNED_AT = 1_700_000_000_000L
        const val COMMAND_STREAM_ID = "stream-id"
        const val COMMAND_DEVICE_FINGERPRINT = "device-fingerprint"
        val COMMAND_KEY_SEED = ByteArray(32) { index -> (index + 1).toByte() }

        /** Deliberately not in alphabetical order — tap order must not be derivable from the grid. */
        val SECRET_WORDS = listOf("Zephyr", "Cedar", "Maple", "Anchor")
        val VECTOR_GRID = (listOf("Zephyr", "Cedar", "Maple", "Anchor") + listOf(
            "Amber", "Beacon", "Comet", "Delta", "Ember", "Forest",
            "Golden", "Ivory", "Jasper", "Lagoon", "Nimble", "Orbit",
        )).sorted()
        val WORD_GRID = (SECRET_WORDS + listOf(
            "Amber", "Beacon", "Comet", "Delta", "Ember", "Forest",
            "Golden", "Ivory", "Jasper", "Lagoon", "Nimble", "Orbit",
        )).sorted()
        val PAIRING_SECRET = PairingSecret(
            words = SECRET_WORDS,
            wordGrid = WORD_GRID,
            gridSeed = "grid-seed-1",
        )
        val COMPANION_EPHEMERAL = newEphemeralKeyPair()
        val CLIENT_EPHEMERAL = newEphemeralKeyPair()
        val INITIAL_CHALLENGE = PairingChallenge(
            challengeId = "challenge-id-1",
            serverChallenge = "server-challenge-1",
            expiresAtEpochMillis = 10_000L,
            wordGrid = WORD_GRID,
            gridSeed = PAIRING_SECRET.gridSeed,
            companionEphemeralPublicKey = COMPANION_EPHEMERAL.publicKeyBase64,
        )
        val ARTIFACT = SessionTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = "fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
            capabilities = linkedSetOf(Capability.FILES, Capability.LAUNCH_CONFIGURATIONS, Capability.TERMINAL),
        )
    }
}
