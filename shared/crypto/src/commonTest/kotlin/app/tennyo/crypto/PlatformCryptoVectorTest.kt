package app.tennyo.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins every platform provider to the same bytes. The ECDSA key, signature, and ECDH key were produced by JCA, so the
 * Node and Security.framework providers are checked against the JVM encodings, not only against themselves.
 */
class PlatformCryptoVectorTest {
    @Test
    fun sha256MatchesTheFipsVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun hmacMatchesRfc4231TestCaseTwo() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun aes256CtrMatchesTheNistVector() {
        // NIST SP 800-38A, F.5.5 CTR-AES256.Encrypt: four blocks, so the big-endian counter carries across blocks.
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToBytes()
        val counter = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".hexToBytes()
        val plaintext = ("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710").hexToBytes()
        val ciphertext = "601ec313775789a5b7a7f504bbf3d228f443e3ca4d62b59aca84e990cacaf5c5" +
                "2b0930daa23de94ce87017ba2d84988ddfc9c58db67aada613c2dd08457941a6"

        assertEquals(ciphertext, aes256Ctr(key, counter, plaintext).toHex())
        assertContentEquals(plaintext, aes256Ctr(key, counter, ciphertext.hexToBytes()))
        assertEquals(ciphertext.take(26), aes256Ctr(key, counter, plaintext.copyOf(13)).toHex())
    }

    @Test
    fun verifiesAJcaSignature() {
        assertTrue(verifyEcdsaP256Sha256(JCA_SPKI, MESSAGE, JCA_SIGNATURE))
    }

    @Test
    fun rejectsATamperedMessageSignatureOrKey() {
        assertFalse(verifyEcdsaP256Sha256(JCA_SPKI, "tennyo-cross-target-vectoR".encodeToByteArray(), JCA_SIGNATURE))
        assertFalse(verifyEcdsaP256Sha256(JCA_SPKI, MESSAGE, JCA_SIGNATURE.flippedAt(JCA_SIGNATURE.size - 1)))
        assertFalse(verifyEcdsaP256Sha256(JCA_SPKI.copyOf(40), MESSAGE, JCA_SIGNATURE))
        assertFalse(verifyEcdsaP256Sha256(JCA_SPKI, MESSAGE, ByteArray(0)))
    }

    @Test
    fun ecdhRoundTripAgreesOnBothSides() {
        EphemeralKeyAgreement().use { first ->
            EphemeralKeyAgreement().use { second ->
                val firstSecret = assertNotNull(first.sharedSecret(second.publicKeySpki))
                val secondSecret = assertNotNull(second.sharedSecret(first.publicKeySpki))

                assertEquals(P256_SHARED_SECRET_BYTES, firstSecret.size)
                assertContentEquals(firstSecret, secondSecret)
            }
        }
    }

    @Test
    fun exportsPublicKeysInTheJcaSpkiEncoding() {
        val spki = EphemeralKeyAgreement().use { it.publicKeySpki }

        assertEquals(JCA_ECDH_SPKI.size, spki.size)
        assertContentEquals(JCA_ECDH_SPKI.copyOf(SPKI_HEADER_BYTES), spki.copyOf(SPKI_HEADER_BYTES))
    }

    @Test
    fun derivesAgainstAJcaPublicKeyAndRejectsAMalformedOne() {
        EphemeralKeyAgreement().use { agreement ->
            assertEquals(P256_SHARED_SECRET_BYTES, agreement.sharedSecret(JCA_ECDH_SPKI)?.size)
            assertNull(agreement.sharedSecret(JCA_ECDH_SPKI.copyOf(40)))
            assertNull(agreement.sharedSecret("not a key".encodeToByteArray()))
        }
    }

    @Test
    fun randomBytesHaveTheRequestedSizeAndVary() {
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)

        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
    }

    private fun ByteArray.flippedAt(index: Int): ByteArray =
        copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

    private companion object {
        val MESSAGE = "tennyo-cross-target-vector".encodeToByteArray()
        val JCA_SPKI = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEK2nsjwfqn6f8WLpnolSLqz2elrFYt6_fvn-uLRbqg7cyF9nuxdqLbtGhDe87" +
                "o93aYTsTmkkMANB0Kd5x4oAnlw").decodeBase64Url()
        val JCA_SIGNATURE = ("MEUCIQDQQY112UVCjNapnngfYYD1EZUXXvJA3oQm2PUWsg1egQIgXzRm7R9BQsLUSaD5ZQCDAeZ2_eDLDAqt2Uwd" +
                "pXv_FTc").decodeBase64Url()
        val JCA_ECDH_SPKI = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEB2VblUvFhsKcSqCshGkvkMPfzTkZTgQe_C56se3vOTG-gBAHpJxeu" +
                "Gqbn5q66vQFhYIZrSHuVBfEyarnaCiF8A").decodeBase64Url()
        const val P256_SHARED_SECRET_BYTES = 32

        /** Algorithm identifier, curve OID, and the uncompressed-point marker: everything but the point itself. */
        const val SPKI_HEADER_BYTES = 27
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
