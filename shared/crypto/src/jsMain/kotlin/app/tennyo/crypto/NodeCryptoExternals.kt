@file:JsModule("node:crypto")

package app.tennyo.crypto

import org.khronos.webgl.Uint8Array

/** The synchronous subset of `node:crypto` the link needs; buffers cross the boundary as `Uint8Array`. */
internal external interface NodeKeyObject {
    fun export(options: dynamic): Uint8Array
}

internal external interface NodeKeyPair {
    val publicKey: NodeKeyObject
    val privateKey: NodeKeyObject
}

internal external fun createPublicKey(options: dynamic): NodeKeyObject

internal external interface NodeCipher {
    fun update(data: Uint8Array): Uint8Array

    fun final(): Uint8Array
}

internal external fun createCipheriv(algorithm: String, key: Uint8Array, iv: Uint8Array): NodeCipher

internal external fun verify(algorithm: String, data: Uint8Array, key: dynamic, signature: Uint8Array): Boolean

internal external fun generateKeyPairSync(type: String, options: dynamic): NodeKeyPair

internal external fun diffieHellman(options: dynamic): Uint8Array

@JsName("randomBytes")
internal external fun nodeRandomBytes(size: Int): Uint8Array
