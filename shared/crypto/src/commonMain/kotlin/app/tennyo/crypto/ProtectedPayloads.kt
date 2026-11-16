package app.tennyo.crypto

import app.tennyo.contract.PROTECTED_PAYLOAD_ALGORITHM
import app.tennyo.contract.ProtectedPayload
import kotlinx.serialization.json.Json

/**
 * Seals and opens [ProtectedPayload] envelopes: AES-256-CTR under a key derived from the response key, then
 * HMAC-SHA-256 under an independent key over the algorithm, protocol version, nonce and ciphertext (encrypt-then-MAC).
 * Opening checks the MAC in constant time before decrypting.
 */
object ProtectedPayloads {
    /** Defaults are encoded so the `algorithm` field is always on the wire. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun seal(plaintext: ByteArray, responseKey: ByteArray, nonce: String): String {
        val keys = SecurityDerivations.protectedPayloadKeys(responseKey)
        val iv = SecurityDerivations.protectedPayloadIv(keys.encryptionKey, nonce)
        val ciphertext = aes256Ctr(keys.encryptionKey, iv, plaintext).toBase64Url()
        val mac = mac(keys.macKey, nonce, ciphertext)
        val payload = ProtectedPayload(nonce = nonce, ciphertext = ciphertext, mac = mac)
        return json.encodeToString(ProtectedPayload.serializer(), payload)
    }

    /** The plaintext, or `null` for a payload that is malformed, of another algorithm, or fails its MAC. */
    fun open(sealed: String, responseKey: ByteArray): ByteArray? {
        val keys = SecurityDerivations.protectedPayloadKeys(responseKey)
        val payload = runCatching { json.decodeFromString(ProtectedPayload.serializer(), sealed) }.getOrNull()
            ?.takeIf { it.algorithm == PROTECTED_PAYLOAD_ALGORITHM }
            ?.takeIf { constantTimeEquals(mac(keys.macKey, it.nonce, it.ciphertext), it.mac) }
            ?: return null
        val iv = SecurityDerivations.protectedPayloadIv(keys.encryptionKey, payload.nonce)
        val ciphertext = payload.ciphertext.decodeBase64UrlOrNull() ?: return null
        return aes256Ctr(keys.encryptionKey, iv, ciphertext)
    }

    private fun mac(macKey: ByteArray, nonce: String, ciphertext: String): String =
        hmacSha256Url(key = macKey, message = SecurityTranscripts.protectedPayloadMac(nonce, ciphertext))
}
