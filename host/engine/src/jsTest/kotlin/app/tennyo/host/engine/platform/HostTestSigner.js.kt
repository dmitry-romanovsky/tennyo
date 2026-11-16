package app.tennyo.host.engine.platform

actual object HostTestSigner {
    actual fun newKey(): HostTestSigningKey =
        NodeTestSigningKey(generateKeyPairSync("ec", jsOptions("namedCurve" to "prime256v1")))

    actual fun sign(key: HostTestSigningKey, message: ByteArray): ByteArray =
        sign(
            "sha256",
            message.asUint8Array(),
            jsOptions("key" to (key as NodeTestSigningKey).keyPair.privateKey, "dsaEncoding" to "der"),
        ).asByteArray()
}

private class NodeTestSigningKey(val keyPair: NodeKeyPair) : HostTestSigningKey {
    override val publicKeySpki: ByteArray =
        keyPair.publicKey.export(jsOptions("format" to "der", "type" to "spki")).asByteArray()
}
