package app.tennyo.mobile.link.transport

import app.tennyo.mobile.link.LinkTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext

class TransportPinsTest {
    @Test
    fun hostnameVerifierAcceptsRememberedPinBeforePlatformTrust() {
        val host = "pin-match.example.test"
        val certificate = TestCertificate(byteArrayOf(1, 2, 3))
        rememberTransportPin(artifact(host, certificate.fingerprint()))
        val delegate = RecordingHostnameVerifier(result = false)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = false,
        ).verify(host, TestSslSession(host, certificate))

        assertTrue(verified)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun twoHostsOnOneAddressKeepTheirOwnPins() {
        // An IDE and an editor on one computer listen on one address, each on its own port and certificate.
        val host = "shared-address.example.test"
        val ide = TestCertificate(byteArrayOf(40, 41, 42))
        val editor = TestCertificate(byteArrayOf(43, 44, 45))
        rememberTransportPin(artifact(host, ide.fingerprint(), port = 63343))
        rememberTransportPin(artifact(host, editor.fingerprint(), port = 63344))
        val verifier = PinnedHostnameVerifier(
            delegate = RecordingHostnameVerifier(result = false),
            allowUnsafeTraffic = false,
        )

        assertTrue(verifier.verify(host, TestSslSession(host, ide, port = 63343)))
        assertTrue(verifier.verify(host, TestSslSession(host, editor, port = 63344)))
        assertFalse(verifier.verify(host, TestSslSession(host, editor, port = 63343)))
    }

    @Test
    fun rememberedTransportHostLimitsOkHttpDiagnosticsToPinnedEndpoints() {
        val host = "diagnostics.example.test"
        val certificate = TestCertificate(byteArrayOf(30, 31, 32))
        rememberTransportPin(artifact(host, certificate.fingerprint()))

        assertTrue(isPinnedTransportHost(host))
        assertFalse(isPinnedTransportHost("unrelated.example.test"))
    }

    @Test
    fun strictPinnedHostRejectsPinMismatchWithoutPlatformTrustFallback() {
        val host = "strict-mismatch.example.test"
        val pinnedCertificate = TestCertificate(byteArrayOf(4, 5, 6))
        val proxyCertificate = TestCertificate(byteArrayOf(7, 8, 9))
        rememberTransportPin(artifact(host, pinnedCertificate.fingerprint()))
        val delegate = RecordingHostnameVerifier(result = true)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = false,
        ).verify(host, TestSslSession(host, proxyCertificate))

        assertFalse(verified)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun pinnedHostRejectsPlatformTrustedProxyAfterPinMismatch() {
        val host = "unsafe-proxy.example.test"
        val pinnedCertificate = TestCertificate(byteArrayOf(10, 11, 12))
        val proxyCertificate = TestCertificate(byteArrayOf(13, 14, 15))
        rememberTransportPin(artifact(host, pinnedCertificate.fingerprint()))
        val delegate = RecordingHostnameVerifier(result = true)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = false,
        ).verify(host, TestSslSession(host, proxyCertificate))

        assertFalse(verified)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun debugUnsafeTrafficAllowsPlatformTrustedProxyAfterPinMismatch() {
        val host = "debug-proxy.example.test"
        val pinnedCertificate = TestCertificate(byteArrayOf(33, 34, 35))
        val proxyCertificate = TestCertificate(byteArrayOf(36, 37, 38))
        rememberTransportPin(artifact(host, pinnedCertificate.fingerprint()))
        val delegate = RecordingHostnameVerifier(result = true)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = true,
        ).verify(host, TestSslSession(host, proxyCertificate))

        assertTrue(verified)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun pinnedHostRejectsUntrustedProxyAfterPinMismatch() {
        val host = "unsafe-untrusted.example.test"
        val pinnedCertificate = TestCertificate(byteArrayOf(16, 17, 18))
        val proxyCertificate = TestCertificate(byteArrayOf(19, 20, 21))
        rememberTransportPin(artifact(host, pinnedCertificate.fingerprint()))
        val delegate = RecordingHostnameVerifier(result = false)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = false,
        ).verify(host, TestSslSession(host, proxyCertificate))

        assertFalse(verified)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun unrememberedHostUsesPlatformTrust() {
        val host = "unremembered.example.test"
        val delegate = RecordingHostnameVerifier(result = true)

        val verified = PinnedHostnameVerifier(
            delegate = delegate,
            allowUnsafeTraffic = false,
        ).verify(host, TestSslSession(host, TestCertificate(byteArrayOf(22, 23, 24))))

        assertTrue(verified)
        assertEquals(1, delegate.calls)
    }

    private fun artifact(host: String, fingerprint: String, port: Int = 63342): LinkTicket =
        LinkTicket(
            endpoint = "https://$host:$port/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = fingerprint,
            projectName = "Tennyo",
            projectPath = "/project",
        )

    private fun TestCertificate.fingerprint(): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        )

    private class RecordingHostnameVerifier(
        private val result: Boolean,
    ) : HostnameVerifier {
        var calls = 0
            private set

        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            calls += 1
            return result
        }
    }

    private class TestSslSession(
        private val host: String,
        private val certificate: X509Certificate,
        private val port: Int = 63342,
    ) : SSLSession {
        override fun getId(): ByteArray = byteArrayOf()
        override fun getSessionContext(): SSLSessionContext? = null
        override fun getCreationTime(): Long = 0L
        override fun getLastAccessedTime(): Long = 0L
        override fun invalidate() = Unit
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) = Unit
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) = Unit
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates(): Array<Certificate> = arrayOf(certificate)
        override fun getLocalCertificates(): Array<Certificate>? = null

        @Suppress("DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()

        override fun getPeerPrincipal(): Principal = certificate.subjectDN
        override fun getLocalPrincipal(): Principal? = null
        override fun getCipherSuite(): String = "TLS_FAKE_WITH_NULL"
        override fun getProtocol(): String = "TLSv1.3"
        override fun getPeerHost(): String = host
        override fun getPeerPort(): Int = port
        override fun getPacketBufferSize(): Int = 0
        override fun getApplicationBufferSize(): Int = 0
    }

    private class TestCertificate(
        private val publicKeyBytes: ByteArray,
    ) : X509Certificate() {
        private val principal = Principal { "CN=Tennyo Test" }

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = principal
        override fun getSubjectDN(): Principal = principal
        override fun getNotBefore(): Date = Date(0L)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = byteArrayOf()
        override fun getSignature(): ByteArray = byteArrayOf()
        override fun getSigAlgName(): String = "NONE"
        override fun getSigAlgOID(): String = "0.0"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = publicKeyBytes
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "TestCertificate"
        override fun getPublicKey(): PublicKey =
            object : PublicKey {
                override fun getAlgorithm(): String = "RSA"
                override fun getFormat(): String = "X.509"
                override fun getEncoded(): ByteArray = publicKeyBytes
            }

        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): Set<String>? = null
        override fun getNonCriticalExtensionOIDs(): Set<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }
}
