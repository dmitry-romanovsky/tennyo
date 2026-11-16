package app.tennyo.mobile.link.pairing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * This device's long-lived P-256 key, kept in the Android Keystore or the iOS Keychain. Its public half and fingerprint
 * identify the device to the IDE; every [app.tennyo.contract.DeviceProof] is signed with it.
 */
expect class DeviceIdentity() {
    val publicKeyBase64: String
    val fingerprint: String
    val clientId: String
    val clientName: String

    /** A fresh random nonce, for pairing attempts and proofs. */
    fun newNonce(): String

    /** An ECDSA P-256 signature over SHA-256 of [message], DER-encoded, as unpadded base64url. */
    fun sign(message: ByteArray): String
}

/** Keystore and Keychain calls can block, so they run here rather than on the caller's thread. */
internal expect val cryptoDispatcher: CoroutineDispatcher

/** The identity fields a request needs, read once off the calling thread. */
data class DeviceIdentitySnapshot(
    val publicKeyBase64: String,
    val fingerprint: String,
    val clientId: String,
    val clientName: String,
)

internal suspend fun DeviceIdentity.snapshot(): DeviceIdentitySnapshot =
    withContext(cryptoDispatcher) {
        DeviceIdentitySnapshot(
            publicKeyBase64 = publicKeyBase64,
            fingerprint = fingerprint,
            clientId = clientId,
            clientName = clientName,
        )
    }

internal suspend fun DeviceIdentity.nextAttemptId(): String = withContext(cryptoDispatcher) { newNonce() }
