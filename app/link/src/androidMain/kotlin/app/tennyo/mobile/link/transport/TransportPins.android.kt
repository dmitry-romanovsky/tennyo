package app.tennyo.mobile.link.transport

import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

actual fun rememberTransportPin(artifact: LinkTicket) {
    TransportCertificatePins.remember(artifact)
}

actual fun isTransportEndpointPinned(endpoint: String): Boolean =
    TransportCertificatePins.isEndpointPinned(endpoint)

internal actual fun transportPinFingerprint(endpoint: String): String? =
    TransportCertificatePins.fingerprintOf(endpoint)

internal fun isPinnedTransportHost(host: String): Boolean =
    TransportCertificatePins.hasPin(host)

fun pinnedTrustManager(): X509TrustManager =
    PinnedTrustManager(defaultTrustManager())

fun pinnedSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
    SSLContext.getInstance("TLS")
        .apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        .socketFactory

/** [allowUnsafeTraffic] is for debug builds only: it trusts a developer-installed proxy CA for traffic inspection. */
fun pinnedHostnameVerifier(allowUnsafeTraffic: Boolean): HostnameVerifier =
    PinnedHostnameVerifier(
        delegate = HttpsURLConnection.getDefaultHostnameVerifier(),
        allowUnsafeTraffic = allowUnsafeTraffic,
    )

/**
 * The certificate each host was pinned to, by address and port: one computer runs several hosts (an IDE and an
 * editor) on one address, each with its own certificate, and a pin for one must not replace the other's.
 */
private object TransportCertificatePins {
    private val fingerprintsByAuthority = ConcurrentHashMap<String, String>()

    fun remember(artifact: LinkTicket) {
        rememberEndpoint(artifact.endpoint, artifact.fingerprint)
        artifact.alternateEndpoints.forEach { rememberEndpoint(it, artifact.fingerprint) }
    }

    fun matches(host: String?, port: Int, certificate: X509Certificate): Boolean {
        val expected = fingerprintsByAuthority[authority(host, port)] ?: return false
        return certificate.publicKeySha256Base64() == expected
    }

    fun rememberObserved(endpoint: String, fingerprint: String) {
        rememberEndpoint(endpoint, fingerprint)
    }

    fun isEndpointPinned(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        val host = normalizeHost(uri.host)
        return host.isNotBlank() &&
            fingerprintsByAuthority.containsKey(authority(host, uri.port.takeIf { it > 0 } ?: HTTPS_PORT))
    }

    fun fingerprintOf(endpoint: String): String? {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
        return fingerprintsByAuthority[authority(uri.host, uri.port.takeIf { it > 0 } ?: HTTPS_PORT)]
    }

    fun hasPin(host: String?): Boolean {
        val prefix = "${normalizeHost(host)}:"
        return fingerprintsByAuthority.keys.any { it.startsWith(prefix) }
    }

    fun matchesAny(certificate: X509Certificate): Boolean {
        val fingerprint = certificate.publicKeySha256Base64()
        return fingerprintsByAuthority.values.any { it == fingerprint }
    }

    private fun rememberEndpoint(endpoint: String, fingerprint: String) {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return
        val host = normalizeHost(uri.host)
        if (host.isNotBlank() && fingerprint.isNotBlank()) {
            fingerprintsByAuthority[authority(host, uri.port.takeIf { it > 0 } ?: HTTPS_PORT)] = fingerprint
        }
    }

    private fun authority(host: String?, port: Int): String = "${normalizeHost(host)}:$port"

    // URI keeps an IPv6 literal's brackets; a TLS session reports the bare address.
    private fun normalizeHost(host: String?): String =
        host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.US).orEmpty()

    private const val HTTPS_PORT = 443
}

@Suppress("CustomX509TrustManager")
internal class PinnedTrustManager(
    private val delegate: X509TrustManager,
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain?.firstOrNull()?.let(TransportCertificatePins::matchesAny) == true) return
        delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
        if (chain?.firstOrNull()?.let(TransportCertificatePins::matchesAny) == true) return
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (chain?.firstOrNull()?.let(TransportCertificatePins::matchesAny) == true) return
        delegate.checkServerTrusted(chain, authType)
    }
}

internal class PinnedHostnameVerifier(
    private val delegate: HostnameVerifier,
    private val allowUnsafeTraffic: Boolean,
) : HostnameVerifier {
    // A pinned host answers from its pin alone; only an unpinned host falls through to the platform.
    @Suppress("ReturnCount")
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val certificate = session?.peerCertificates?.firstOrNull() as? X509Certificate
        if (TransportCertificatePins.hasPin(hostname)) {
            if (certificate != null && TransportCertificatePins.matches(hostname, session.peerPort, certificate)) {
                return true
            }
            return allowUnsafeTraffic && delegate.verify(hostname, session)
        }
        return delegate.verify(hostname, session)
    }
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private fun X509Certificate.publicKeySha256Base64(): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

actual fun rememberObservedTransportPin(endpoint: String, fingerprint: String) {
    TransportCertificatePins.rememberObserved(endpoint, fingerprint)
}

actual suspend fun observeTransportFingerprint(endpoint: String): String? =
    withContext(Dispatchers.IO) {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return@withContext null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return@withContext null
        val port = uri.port.takeIf { it > 0 } ?: return@withContext null
        runCatching {
            observingSocketFactory().createSocket() as SSLSocket
        }.getOrNull()?.use { socket ->
            runCatching {
                socket.soTimeout = OBSERVE_TIMEOUT_MILLIS
                socket.connect(InetSocketAddress(host, port), OBSERVE_TIMEOUT_MILLIS)
                socket.startHandshake()
                (socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.publicKeySha256Base64()
            }.getOrNull()
        }
    }

private const val OBSERVE_TIMEOUT_MILLIS = 1_500

/**
 * A socket factory that accepts any certificate, for [observeTransportFingerprint] alone. It is built per call and
 * never reaches the shared client, so nothing else can inherit this trust.
 */
private fun observingSocketFactory(): SSLSocketFactory =
    SSLContext.getInstance("TLS")
        .apply { init(null, arrayOf<TrustManager>(ObservingTrustManager), null) }
        .socketFactory

@Suppress("CustomX509TrustManager")
private object ObservingTrustManager : X509TrustManager {
    // Accepting anything is the point: the probe is recording what was presented, not deciding to trust it.
    @Suppress("TrustAllX509TrustManager")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    @Suppress("TrustAllX509TrustManager")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
