package app.tennyo.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

private val secureRandom = SecureRandom()

actual fun verifyEcdsaP256Sha256(spki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean =
    runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(ecPublicKey(spki))
            update(message)
            verify(derSignature)
        }
    }.getOrDefault(false)

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun aes256Ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
    require(key.size == AES_256_KEY_BYTES && iv.size == AES_BLOCK_BYTES) {
        "AES-256-CTR needs a 32-byte key and a 16-byte IV"
    }
    return Cipher.getInstance("AES/CTR/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(input)
    }
}

/**
 * Generated in memory on Android too rather than in the Android keystore: keystore keys are non-extractable and
 * provisioned for signing, and an ephemeral key must not outlive the attempt anyway.
 */
actual class EphemeralKeyAgreement actual constructor() : AutoCloseable {
    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec(P256_CURVE), secureRandom) }
        .generateKeyPair()

    actual val publicKeySpki: ByteArray = keyPair.public.encoded

    actual fun sharedSecret(peerSpki: ByteArray): ByteArray? =
        runCatching {
            KeyAgreement.getInstance("ECDH").run {
                init(keyPair.private)
                doPhase(ecPublicKey(peerSpki), true)
                generateSecret()
            }
        }.getOrNull()

    /** Best effort: most JCE private keys refuse destruction, and the garbage collector reclaims the rest. */
    actual override fun close() {
        runCatching { (keyPair.private as? Destroyable)?.destroy() }
    }
}

private fun ecPublicKey(spki: ByteArray): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

private const val P256_CURVE = "secp256r1"
