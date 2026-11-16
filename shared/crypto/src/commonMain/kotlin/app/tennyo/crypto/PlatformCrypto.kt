package app.tennyo.crypto

/**
 * The asymmetric operations and randomness that go to the platform provider: JCA on the JVM host and Android,
 * Security.framework on iOS, `node:crypto` on the VS Code host. Every operation is synchronous. Malformed peer input
 * yields `false` or `null`, never an exception.
 */
expect fun verifyEcdsaP256Sha256(spki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean

/** Bytes from the platform CSPRNG. */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * AES-256 in counter mode with a big-endian counter over the whole 16-byte [iv]; the same call encrypts and decrypts.
 * Only [ProtectedPayloads] uses it, always under a MAC.
 */
expect fun aes256Ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray

internal const val AES_256_KEY_BYTES = 32
internal const val AES_BLOCK_BYTES = 16

/**
 * One ephemeral EC P-256 key pair for a single pairing, resume, or discovery attempt.
 *
 * Pair material travels under a key derived from this exchange rather than from the tapped words or the pair secret;
 * those only authenticate the exchange by MACing a transcript that covers both public keys. That split is what stops
 * a recorded exchange from being unwrapped offline. Forward secrecy is the point, so the private key must not outlive
 * the attempt — always hold it inside [use].
 */
expect class EphemeralKeyAgreement() : AutoCloseable {
    /** DER SubjectPublicKeyInfo, the form both sides exchange as base64url. */
    val publicKeySpki: ByteArray

    /**
     * The raw ECDH secret (the X coordinate) agreed with [peerSpki], or `null` when that key is malformed or
     * off-curve. Feed it through [SecurityDerivations.pairMaterialKey]; never use it directly.
     */
    fun sharedSecret(peerSpki: ByteArray): ByteArray?

    /** Discards the private key. */
    override fun close()
}

/** [EphemeralKeyAgreement.publicKeySpki] in its wire form. */
val EphemeralKeyAgreement.publicKeyBase64: String get() = publicKeySpki.toBase64Url()

/** [EphemeralKeyAgreement.sharedSecret] for a base64url peer key; `null` when the key does not decode. */
fun EphemeralKeyAgreement.sharedSecret(peerPublicKeyBase64: String): ByteArray? =
    peerPublicKeyBase64.decodeBase64UrlOrNull()?.let(::sharedSecret)
