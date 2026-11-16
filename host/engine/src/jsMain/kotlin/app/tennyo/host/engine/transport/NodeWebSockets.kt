// The named exports of the `ws` package. A file-level @JsModule binds each declaration to an export of that name;
// putting @JsModule on one declaration would bind the module itself, which is `ws`'s client class.
@file:JsModule("ws")
@file:JsNonModule
@file:Suppress("UnusedParameter")

package app.tennyo.host.engine.transport

import org.khronos.webgl.Uint8Array

/** RFC 6455 framing, masking and continuation handling; hand-rolling it would be far more code than the binding. */
external class WebSocketServer(options: dynamic) {
    fun handleUpgrade(
        request: NodeIncomingMessage,
        socket: NodeSocket,
        head: Uint8Array,
        onUpgraded: (NodeWebSocket) -> Unit,
    )
}

/** The `ws` client, which the engine's own tests use to speak to the listener as the phone does. */
external class WebSocket(url: String, options: dynamic) {
    fun send(data: String)

    fun close()

    fun on(event: String, listener: dynamic)
}
