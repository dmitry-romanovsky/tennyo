package app.tennyo.host.engine.platform

/** A device key the tests hold both halves of, standing in for the mobile app's hardware-backed key. */
interface HostTestSigningKey {
    val publicKeySpki: ByteArray
}

/** Signs like the mobile app: ECDSA P-256 over SHA-256, DER-encoded. The engine itself only ever verifies. */
expect object HostTestSigner {
    fun newKey(): HostTestSigningKey

    fun sign(key: HostTestSigningKey, message: ByteArray): ByteArray
}
