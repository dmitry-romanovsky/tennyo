package app.tennyo.crypto

import kotlin.io.encoding.Base64

/** Base64url without padding: every key, nonce, token, and MAC on the Tennyo wire. */
private val base64UrlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Accepts the URL-safe alphabet with or without padding, as `java.util.Base64.getUrlDecoder()` does. */
private val base64UrlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

fun ByteArray.toBase64Url(): String = base64UrlEncoder.encode(this)

/** Standard padded base64 — the one place the protocol uses it, for the transport fingerprint both sides compare. */
fun ByteArray.toBase64(): String = Base64.Default.encode(this)

/** Throws [IllegalArgumentException] on input outside the URL-safe alphabet. */
fun String.decodeBase64Url(): ByteArray = base64UrlDecoder.decode(this)

/** [decodeBase64Url] for peer input, where malformed text is expected and answered with `null`. */
fun String.decodeBase64UrlOrNull(): ByteArray? = runCatching { decodeBase64Url() }.getOrNull()
