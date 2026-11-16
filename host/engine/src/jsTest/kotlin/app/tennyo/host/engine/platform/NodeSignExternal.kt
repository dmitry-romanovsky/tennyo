@file:JsModule("node:crypto")

package app.tennyo.host.engine.platform

import org.khronos.webgl.Uint8Array

/** The `node:crypto` signing half the production code never needs: tests sign what the host verifies. */
internal external interface NodeKeyObject {
    fun export(options: dynamic): Uint8Array
}

internal external interface NodeKeyPair {
    val publicKey: NodeKeyObject
    val privateKey: NodeKeyObject
}

internal external fun generateKeyPairSync(type: String, options: dynamic): NodeKeyPair

internal external fun sign(algorithm: String, data: Uint8Array, key: dynamic): Uint8Array
