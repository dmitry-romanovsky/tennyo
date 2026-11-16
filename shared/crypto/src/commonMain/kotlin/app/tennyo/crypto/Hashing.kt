package app.tennyo.crypto

import okio.ByteString.Companion.toByteString

/**
 * SHA-256 and HMAC-SHA256 come from okio on every target, so the app and every host hash the same bytes the same way;
 * only asymmetric operations and randomness go to the platform ([PlatformCrypto]).
 */
fun sha256(data: ByteArray): ByteArray = data.toByteString().sha256().toByteArray()

fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    message.toByteString().hmacSha256(key.toByteString()).toByteArray()

/** Unpadded base64url SHA-256 of the UTF-8 [value] — the hash form every security transcript carries. */
fun sha256Url(value: String): String = sha256(value.encodeToByteArray()).toBase64Url()

/** Unpadded base64url HMAC-SHA256 of the UTF-8 [message] — the form every proof and MAC travels in. */
fun hmacSha256Url(key: ByteArray, message: String): String = hmacSha256(key, message.encodeToByteArray()).toBase64Url()
