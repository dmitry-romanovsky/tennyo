package app.tennyo.mobile.link.transport

import app.tennyo.crypto.P256Spki
import app.tennyo.crypto.sha256
import app.tennyo.crypto.toBase64
import app.tennyo.mobile.link.LinkTicket
import io.ktor.client.engine.darwin.certificates.CertificatePinner
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyKey
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef
import platform.darwin.NSObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual fun rememberTransportPin(artifact: LinkTicket) {
    IosRemoteTransportCertificatePins.remember(artifact)
}

actual fun isTransportEndpointPinned(endpoint: String): Boolean =
    IosRemoteTransportCertificatePins.isEndpointPinned(endpoint)

internal actual fun transportPinFingerprint(endpoint: String): String? {
    val key = endpoint.pinKeyOrNull() ?: return null
    return IosRemoteTransportCertificatePins.fingerprintForKey(key)
}

private typealias PinnedChallengeHandler<Disposition> =
    (NSURLSession, NSURLSessionTask, NSURLAuthenticationChallenge, (Disposition, NSURLCredential?) -> Unit) -> Unit

// Darwin's challenge disposition is a numeric typealias.
// Let handleChallenge provide the target KLIB shape.
@OptIn(ExperimentalForeignApi::class)
fun <Disposition> pinnedChallengeHandler(
    allowUnsafeTraffic: Boolean = false,
): PinnedChallengeHandler<Disposition> =
    { session, task, challenge, completionHandler ->
        val space = challenge.protectionSpace
        val fingerprint = IosRemoteTransportCertificatePins.fingerprintFor(space.host, space.port.toInt())
        if (fingerprint == null) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.asCompletionDisposition(), null)
        } else {
            val pinner = CertificatePinner.Builder()
                .add(challenge.protectionSpace.host, "sha256/$fingerprint")
                .validateTrust(false)
                .build()
            var completed = false
            try {
                pinner.invoke(session, task, challenge) { disposition, credential ->
                    completed = true
                    // CertificatePinner accepts the pinned certificate by returning UseCredential, but
                    // does not attach a credential. Without one URLSession falls back to default trust
                    // evaluation, which rejects the self-signed IDE certificate (errSSLXCertChainInvalid,
                    // -9807). Build the credential from the server trust ourselves so the pinned
                    // certificate is actually used.
                    val effectiveCredential =
                        if (disposition.isUseCredentialDisposition() && credential == null) {
                            challenge.protectionSpace.serverTrust?.let { NSURLCredential.credentialForTrust(it) }
                        } else {
                            credential
                        }
                    completionHandler(disposition.asCompletionDisposition(), effectiveCredential)
                }
            } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
                // Whatever the pinner threw, the challenge still owes URLSession an answer; a strict session
                // rethrows, and only a session that allows unsafe traffic falls back to default handling.
                if (!allowUnsafeTraffic || completed) {
                    throw throwable
                }
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.asCompletionDisposition(), null)
            }
        }
    }

@Suppress("UNCHECKED_CAST")
private fun <Disposition> Any?.asCompletionDisposition(): Disposition = this as Disposition

private fun Int.isUseCredentialDisposition(): Boolean =
    toLong() == NSURLSessionAuthChallengeUseCredential

private fun Long.isUseCredentialDisposition(): Boolean =
    this == NSURLSessionAuthChallengeUseCredential

/**
 * The certificate each host was pinned to, by address and port: one computer runs several hosts (an IDE and an
 * editor) on one address, each with its own certificate, and a pin for one must not replace the other's.
 */
private object IosRemoteTransportCertificatePins {
    private val lock = NSLock()
    private val fingerprintsByAuthority = mutableMapOf<String, String>()

    fun remember(artifact: LinkTicket) {
        rememberEndpoint(artifact.endpoint, artifact.fingerprint)
        artifact.alternateEndpoints.forEach { rememberEndpoint(it, artifact.fingerprint) }
    }

    fun rememberObserved(endpoint: String, fingerprint: String) {
        rememberEndpoint(endpoint, fingerprint)
    }

    fun isEndpointPinned(endpoint: String): Boolean {
        val key = endpoint.pinKeyOrNull() ?: return false
        lock.lock()
        return try {
            key in fingerprintsByAuthority
        } finally {
            lock.unlock()
        }
    }

    fun fingerprintFor(host: String, port: Int): String? = fingerprintForKey(pinKey(host, port))

    fun fingerprintForKey(key: String): String? {
        lock.lock()
        return try {
            fingerprintsByAuthority[key]
        } finally {
            lock.unlock()
        }
    }

    private fun rememberEndpoint(endpoint: String, fingerprint: String) {
        val key = endpoint.pinKeyOrNull() ?: return
        if (fingerprint.isNotBlank()) {
            lock.lock()
            try {
                fingerprintsByAuthority[key] = fingerprint
            } finally {
                lock.unlock()
            }
        }
    }
}

/** The pin key for an endpoint URL: its host (an IPv6 literal without brackets) and port. */
private fun String.pinKeyOrNull(): String? {
    val authority = substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .takeIf { it.isNotBlank() }
        ?: return null
    val (host, port) = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']') to authority.substringAfter("]:", "")
    } else {
        authority.substringBefore(':') to authority.substringAfter(':', "")
    }
    return host.trim().takeIf { it.isNotBlank() }?.let { pinKey(it, port.toIntOrNull() ?: HTTPS_PORT) }
}

private fun pinKey(host: String, port: Int): String = "${host.lowercase()}:$port"

private const val HTTPS_PORT = 443

actual fun rememberObservedTransportPin(endpoint: String, fingerprint: String) {
    IosRemoteTransportCertificatePins.rememberObserved(endpoint, fingerprint)
}

/**
 * Completes one TLS handshake with [endpoint] through a session of its own, accepting whatever certificate is
 * presented, and reports its SPKI SHA-256. The permissive delegate lives and dies with this call; the shared
 * client keeps its pinned handler.
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun observeTransportFingerprint(endpoint: String): String? = suspendCoroutine { continuation ->
    val url = NSURL(string = endpoint)
    val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
        timeoutIntervalForRequest = OBSERVE_TIMEOUT_SECONDS
        timeoutIntervalForResource = OBSERVE_TIMEOUT_SECONDS
    }
    lateinit var session: NSURLSession
    val delegate = ObservingSessionDelegate(continuation) { session.finishTasksAndInvalidate() }
    session = NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue = null)
    // The handshake is the whole point; the delegate resumes once, whatever the request itself ends as.
    session.dataTaskWithURL(url).resume()
}

private const val OBSERVE_TIMEOUT_SECONDS = 1.5

/**
 * Answers the server-trust challenge with the trust it was handed, recording the leaf's SPKI SHA-256 on the way
 * through. It resumes its continuation exactly once, whether the challenge arrives, the request fails, or neither.
 */
@OptIn(ExperimentalForeignApi::class)
private class ObservingSessionDelegate(
    private val continuation: Continuation<String?>,
    private val onFinished: () -> Unit,
) : NSObject(), NSURLSessionTaskDelegateProtocol {
    private val resumed = atomic(false)
    private var observed: String? = null

    override fun URLSession(
        session: NSURLSession,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        val trust = didReceiveChallenge.protectionSpace.serverTrust
        observed = trust?.let(::leafPublicKeySha256Base64)
        if (trust == null) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
        }
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        finish()
    }

    /** Resumes with whatever the challenge observed, exactly once, however the request itself ended. */
    private fun finish() {
        if (resumed.compareAndSet(expect = false, update = true)) {
            onFinished()
            continuation.resume(observed)
        }
    }
}

/** The SPKI SHA-256 of [trust]'s leaf certificate, in the standard base64 the host reports it as. */
@OptIn(ExperimentalForeignApi::class)
private fun leafPublicKeySha256Base64(trust: SecTrustRef): String? {
    val certificate = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
    val key = SecCertificateCopyKey(certificate) ?: return null
    val rawPoint = try {
        SecKeyCopyExternalRepresentation(key, null)?.let { data ->
            try {
                data.toByteArray()
            } finally {
                CFRelease(data)
            }
        }
    } finally {
        CFRelease(key)
    } ?: return null
    val spki = runCatching { P256Spki.fromRawPoint(rawPoint) }.getOrNull() ?: return null
    return sha256(spki).toBase64()
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { index -> bytes[index].toByte() }
}
