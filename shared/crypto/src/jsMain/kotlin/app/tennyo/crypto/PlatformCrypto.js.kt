package app.tennyo.crypto

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

actual fun verifyEcdsaP256Sha256(spki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean =
    runCatching {
        verify(
            "sha256",
            message.asUint8Array(),
            jsOptions("key" to spkiPublicKey(spki), "dsaEncoding" to "der"),
            derSignature.asUint8Array(),
        )
    }.getOrDefault(false)

actual fun secureRandomBytes(size: Int): ByteArray = nodeRandomBytes(size).asByteArray()

actual fun aes256Ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
    require(key.size == AES_256_KEY_BYTES && iv.size == AES_BLOCK_BYTES) {
        "AES-256-CTR needs a 32-byte key and a 16-byte IV"
    }
    val cipher = createCipheriv("aes-256-ctr", key.asUint8Array(), iv.asUint8Array())
    return cipher.update(input.asUint8Array()).asByteArray() + cipher.final().asByteArray()
}

/** Node key objects have no destroy call; [close] drops the reference so the private key becomes collectable. */
actual class EphemeralKeyAgreement actual constructor() : AutoCloseable {
    private var keyPair: NodeKeyPair? = generateKeyPairSync("ec", jsOptions("namedCurve" to P256_CURVE))

    actual val publicKeySpki: ByteArray =
        checkNotNull(keyPair).publicKey.export(jsOptions("format" to "der", "type" to "spki")).asByteArray()

    actual fun sharedSecret(peerSpki: ByteArray): ByteArray? {
        val privateKey = keyPair?.privateKey ?: return null
        return runCatching {
            diffieHellman(jsOptions("privateKey" to privateKey, "publicKey" to spkiPublicKey(peerSpki))).asByteArray()
        }.getOrNull()
    }

    actual override fun close() {
        keyPair = null
    }
}

private fun spkiPublicKey(spki: ByteArray): NodeKeyObject =
    createPublicKey(jsOptions("key" to spki.asUint8Array(), "format" to "der", "type" to "spki"))

/** Kotlin/JS backs `ByteArray` with an `Int8Array`, so both directions are views over the same memory, not copies. */
private fun ByteArray.asUint8Array(): Uint8Array {
    val view = unsafeCast<Int8Array>()
    return Uint8Array(view.buffer, view.byteOffset, view.length)
}

private fun Uint8Array.asByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

private fun jsOptions(vararg entries: Pair<String, Any?>): dynamic {
    val options = js("{}")
    entries.forEach { (key, value) -> options[key] = value }
    return options
}

/** Node's name for the curve JCA calls `secp256r1`. */
private const val P256_CURVE = "prime256v1"
