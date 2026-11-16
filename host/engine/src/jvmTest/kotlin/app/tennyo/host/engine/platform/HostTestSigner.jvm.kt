package app.tennyo.host.engine.platform

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

actual object HostTestSigner {
    actual fun newKey(): HostTestSigningKey =
        JcaTestSigningKey(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
        )

    actual fun sign(key: HostTestSigningKey, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign((key as JcaTestSigningKey).keyPair.private)
            update(message)
            sign()
        }
}

private class JcaTestSigningKey(val keyPair: KeyPair) : HostTestSigningKey {
    override val publicKeySpki: ByteArray = keyPair.public.encoded
}
