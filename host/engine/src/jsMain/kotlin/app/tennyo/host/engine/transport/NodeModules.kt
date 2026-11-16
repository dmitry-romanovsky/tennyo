// An external declaration names the JS signature it binds to; nothing in Kotlin reads those parameters.
@file:Suppress("UnusedParameter")

package app.tennyo.host.engine.transport

import org.khronos.webgl.Uint8Array

/**
 * The Node surface the listener uses, declared where it is used rather than pulled from a wrapper library: the
 * listener needs five calls from three built-in modules, and a binding file is smaller than the dependency.
 */
@JsModule("node:https")
@JsNonModule
external object NodeHttps {
    fun createServer(options: dynamic): NodeHttpsServer
}

external interface NodeHttpsServer {
    fun listen(port: Int, host: String, callback: () -> Unit)
    fun on(event: String, listener: dynamic)
    fun close(callback: () -> Unit)
    fun address(): dynamic
}

external interface NodeIncomingMessage {
    val method: String
    val url: String
    val httpVersion: String
    val rawHeaders: Array<String>
    val socket: NodeSocket
    fun on(event: String, listener: dynamic)
    fun destroy()
}

external interface NodeSocket {
    val remoteAddress: String?
    fun write(data: String): Boolean

    /** Flushes [data] and then half-closes; unlike [destroy] it cannot drop bytes still in the write buffer. */
    fun end(data: String)

    fun destroy()
    fun on(event: String, listener: dynamic)
}

external interface NodeServerResponse {
    val socket: NodeSocket?
}

@JsModule("node:os")
@JsNonModule
external object NodeOs {
    fun networkInterfaces(): dynamic
}

/** Certificate issuance has no Node built-in; `selfsigned` is the smallest package that signs an X.509 pair. */
@JsModule("selfsigned")
@JsNonModule
external object SelfSigned {
    fun generate(attributes: Array<dynamic>, options: dynamic): dynamic
}

external interface NodeWebSocket {
    fun send(data: String, onSent: (dynamic) -> Unit)
    fun close(code: Int, reason: String)
    fun on(event: String, listener: dynamic)
}

@JsModule("node:crypto")
@JsNonModule
external object NodeCrypto {
    fun createHash(algorithm: String): NodeHash

    fun createPublicKey(key: String): NodeKeyObject

    fun randomUUID(): String
}

external interface NodeKeyObject {
    fun export(options: dynamic): Uint8Array
}

external interface NodeHash {
    fun update(data: Uint8Array): NodeHash
    fun digest(encoding: String): String
}
