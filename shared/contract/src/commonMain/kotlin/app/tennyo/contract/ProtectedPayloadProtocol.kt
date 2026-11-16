package app.tennyo.contract

import kotlinx.serialization.Serializable

/** Wire value of the `algorithm` field: AES-256-CTR, then HMAC-SHA-256 over the ciphertext, keys split by HKDF. */
const val PROTECTED_PAYLOAD_ALGORITHM: String = "aes-256-ctr+hmac-sha256"

/**
 * An encrypt-then-MAC envelope around pair material or a connection ticket, carried as a JSON string field. Sealing
 * and opening live in `:shared:crypto`.
 */
@Serializable
data class ProtectedPayload(
    val algorithm: String = PROTECTED_PAYLOAD_ALGORITHM,
    val nonce: String,
    val ciphertext: String,
    val mac: String,
)
