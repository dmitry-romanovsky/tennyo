package app.tennyo.crypto

import app.tennyo.contract.PROTECTED_PAYLOAD_ALGORITHM
import app.tennyo.contract.ProtectedPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The constructions both sides call, checked against the frozen outputs in [SecurityTranscriptVectorTest]. */
class LinkConstructionsTest {
    @Test
    fun constantTimeEqualsTellsApartInputsThatDifferOnlyInTheLastByte() {
        val first = "0123456789abcdef0123456789abcdef".encodeToByteArray()
        val second = first.copyOf().also { it[it.lastIndex] = 'g'.code.toByte() }

        assertFalse(constantTimeEquals(first, second))
        assertTrue(constantTimeEquals(first, first.copyOf()))
    }

    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        assertTrue(constantTimeEquals("proof-a", "proof-a"))
        assertFalse(constantTimeEquals("proof-a", "proof-b"))
        assertFalse(constantTimeEquals("proof", "proof-a"))
        assertFalse(constantTimeEquals("", "a"))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    @Test
    fun rememberedPairProofMatchesTheFrozenVector() {
        val proof = LinkProofs.rememberedPair("pair-secret", REMEMBERED_PAIR_TRANSCRIPT)

        assertEquals("24TSq2Ujkg0BXTRiLATSSvHckhOb2Jkf97UdchtcDOw", proof)
        assertTrue(LinkProofs.matches(proof, "24TSq2Ujkg0BXTRiLATSSvHckhOb2Jkf97UdchtcDOw"))
        assertFalse(LinkProofs.matches(proof, "pkjuDVqdX5TpzCB7DXAHNs5Gt4iFTAQuP8iuL1ioMnG"))
    }

    @Test
    fun opensTheFrozenProtectedPayload() {
        val frozen = ProtectedPayload(nonce = NONCE, ciphertext = CIPHERTEXT, mac = MAC)
        val sealed = ProtectedPayloads.seal(PLAINTEXT.encodeToByteArray(), RESPONSE_KEY, NONCE)

        assertEquals(frozen, Json.decodeFromString(ProtectedPayload.serializer(), sealed))
        assertTrue(sealed.contains("\"algorithm\":\"$PROTECTED_PAYLOAD_ALGORITHM\""))
        assertEquals(PLAINTEXT, ProtectedPayloads.open(sealed, RESPONSE_KEY)?.decodeToString())
    }

    @Test
    fun rejectsATamperedWrongKeyOrForeignPayload() {
        val sealed = ProtectedPayloads.seal(PLAINTEXT.encodeToByteArray(), RESPONSE_KEY, NONCE)
        val payload = Json.decodeFromString(ProtectedPayload.serializer(), sealed)
        val json = Json { encodeDefaults = true }
        fun reencoded(changed: ProtectedPayload) = json.encodeToString(ProtectedPayload.serializer(), changed)

        assertNull(ProtectedPayloads.open(sealed, "another-response-key-0123456789ab".encodeToByteArray()))
        assertNull(ProtectedPayloads.open(reencoded(payload.copy(ciphertext = "A" + payload.ciphertext.drop(1))), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open(reencoded(payload.copy(mac = "A" + payload.mac.drop(1))), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open(reencoded(payload.copy(ciphertext = payload.ciphertext.dropLast(4))), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open(sealed.dropLast(10), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open(reencoded(payload.copy(nonce = payload.nonce.reversed())), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open(reencoded(payload.copy(algorithm = "$PROTECTED_PAYLOAD_ALGORITHM-2")), RESPONSE_KEY))
        assertNull(ProtectedPayloads.open("not json", RESPONSE_KEY))
    }

    @Test
    fun sealedPayloadsRoundTrip() {
        val plaintext = secureRandomBytes(100)
        val nonce = secureRandomBytes(24).toBase64Url()

        assertContentEquals(plaintext, ProtectedPayloads.open(ProtectedPayloads.seal(plaintext, RESPONSE_KEY, nonce), RESPONSE_KEY))
    }

    private companion object {
        val RESPONSE_KEY = "response-key-0123456789abcdef012".encodeToByteArray()
        const val NONCE = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY"
        const val PLAINTEXT = """{"pairId":"pair-1","pairSecret":"pair-secret","expiresAtEpochMillis":1800000000000,""" +
            """"deviceLabel":"Pixel 9 Android 17","companionId":"companion-1"}"""
        const val CIPHERTEXT = "xA-sw_ZJjfu9tJtjt2KqVILZOqhz95jUYalt4xq3ZJXzxz1HxZ2kGEXEu9oCzuO1AM1cb8P8TcPaBpUK1ufwst6KZKQJ8KF8leva3rpwzBZRMgrZfPH9wU_KCJGqmnRsFllxQSdBAAJlL1zk6smrrB6JsFFTYAuo4Q-znJCXoKiiowk0Gjf3fBa8vIRpfHZMBTM"
        const val MAC = "2-5PHxxXIjrpIlLWVimHcx9FufkZE0SttFnY8zqCfJ4"
        const val REMEMBERED_PAIR_TRANSCRIPT =
            "tennyo-remembered-pair-resume\n2\n/api/tennyo/link/pair\nchallenge-1\nserver-challenge-1\nsession-id\n" +
                "wQHpEUaclpFxBAtQ1wVDMTz5aP3vW6zHgHdvj7OZqzY\ntransport-fingerprint\ndevice-fingerprint\nattempt-1\n" +
                "companion-ephemeral\nclient-ephemeral"
    }
}
