@file:Suppress("DEPRECATION")

package app.tennyo.host.intellij

import com.intellij.ide.passwordSafe.PasswordSafe
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

internal data class TransportIdentity(
    val fingerprint: String,
    val sslContext: SslContext,
)

private data class StoredTransportIdentity(
    val fingerprint: String,
    val certificatePem: String,
    val privateKeyPem: String,
)

internal fun loadOrCreatePersistentTransportIdentity(
    random: SecureRandom = SecureRandom(),
): TransportIdentity {
    storedTransportIdentity()?.let { return it.toRuntimeIdentity() }
    val created = newStoredTransportIdentity(random)
    PasswordSafe.instance.set(
        DISCOVERY_TRANSPORT_ATTRIBUTES,
        DISCOVERY_TRANSPORT_ATTRIBUTES.credentials(created.encode()),
    )
    return created.toRuntimeIdentity()
}

private fun newStoredTransportIdentity(
    random: SecureRandom,
): StoredTransportIdentity {
    val certificate = SelfSignedCertificate("tennyo-mobile-${UUID.randomUUID()}.local", random, 2048)
    return try {
        StoredTransportIdentity(
            fingerprint = certificate.cert().publicKeySha256Base64(),
            certificatePem = pemEncodeBytes(certificate.cert().encoded, "CERTIFICATE"),
            privateKeyPem = pemEncodeBytes(certificate.key().encoded, "PRIVATE KEY"),
        )
    } finally {
        certificate.delete()
    }
}

private fun storedTransportIdentity(): StoredTransportIdentity? =
    PasswordSafe.instance.get(DISCOVERY_TRANSPORT_ATTRIBUTES)?.password
        ?.toString()
        ?.let { encoded ->
            encoded.substringBefore("\n")
                .takeIf { it.isNotBlank() }
                ?.let { fingerprint ->
                    val certificatePem = encoded.substringAfter("\n", "").substringBefore("\n---KEY---\n")
                    val privateKeyPem = encoded.substringAfter("\n---KEY---\n", "")
                    if (certificatePem.isBlank() || privateKeyPem.isBlank()) {
                        null
                    } else {
                        StoredTransportIdentity(
                            fingerprint = fingerprint,
                            certificatePem = certificatePem,
                            privateKeyPem = privateKeyPem,
                        )
                    }
                }
        }

private fun StoredTransportIdentity.encode(): String =
    buildString {
        append(fingerprint)
        append('\n')
        append(certificatePem)
        append("\n---KEY---\n")
        append(privateKeyPem)
    }

private fun StoredTransportIdentity.toRuntimeIdentity(): TransportIdentity {
    val certificate = certificatePem.pemDecodeCertificate()
    val privateKey = privateKeyPem.pemDecodePrivateKey()
    return TransportIdentity(
        fingerprint = fingerprint,
        sslContext = SslContextBuilder
        .forServer(privateKey, certificate)
        .protocols("TLSv1.3", "TLSv1.2")
            .build(),
    )
}

private fun java.security.cert.Certificate.publicKeySha256Base64(): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

private fun pemEncodeBytes(bytes: ByteArray, label: String): String {
    val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes)
    return "-----BEGIN $label-----\n$base64\n-----END $label-----"
}

private fun String.pemPayload(): ByteArray =
    replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "")
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\n", "")
        .replace("\r", "")
        .let { Base64.getDecoder().decode(it) }

private fun String.pemDecodeCertificate(): X509Certificate =
    CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pemPayload())) as X509Certificate

private fun String.pemDecodePrivateKey(): PrivateKey =
    KeyFactory.getInstance("RSA")
        .generatePrivate(PKCS8EncodedKeySpec(pemPayload()))

private val DISCOVERY_TRANSPORT_ATTRIBUTES =
    hostCredentialAttributes("discoveryTransportIdentity")
