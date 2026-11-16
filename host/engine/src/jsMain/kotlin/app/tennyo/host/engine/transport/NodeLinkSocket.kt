package app.tennyo.host.engine.transport

import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkLimits
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.session.LinkChannel

/** One upgraded WebSocket as the engine sees it. `ws` reports a failed send through the send callback. */
private class NodeLinkSocket(private val socket: NodeWebSocket) : LinkSocket {
    private var failed = false

    override fun sendText(text: String, onSendFailed: (Throwable?) -> Unit): Boolean {
        if (failed) return false
        socket.send(text) { failure ->
            if (failure != null && failure != undefined) {
                failed = true
                onSendFailed(null)
            }
        }
        return true
    }

    override fun close(code: Int, reason: String) = socket.close(code, reason)
}

/**
 * The channel half of the Node listener: it completes the WebSocket handshake and hands each text message to a
 * [LinkChannel], which is where the engine authenticates the first frame. The size ceiling matches the one the
 * IntelliJ listener sets on its frame aggregator, so an oversized frame is refused on both hosts at the same point.
 */
internal class NodeChannelUpgrades {
    private val sockets = WebSocketServer(jsOptions("noServer" to true, "maxPayload" to MAX_FRAME_BYTES))

    fun accept(request: NodeIncomingMessage, socket: NodeSocket, head: dynamic) {
        sockets.handleUpgrade(request, socket, head) { webSocket ->
            val channel = LinkChannel(NodeLinkSocket(webSocket))
            webSocket.on("message") { data: dynamic, isBinary: Boolean ->
                // The channel speaks text only; a binary frame is not a frame it could read.
                if (isBinary) {
                    channel.fail(LinkFaultCode.INVALID_CALL, "The channel carries text frames only.")
                } else {
                    channel.receive(data.toString("utf8") as String)
                }
            }
            js("setTimeout")({ channel.expireUnopened() }, LinkChannel.OPEN_DEADLINE_MILLIS.toInt())
            webSocket.on("close") { channel.closed() }
            webSocket.on("error") { channel.closed() }
        }
    }

    private companion object {
        // A frame holds up to LinkLimits.MAX_FRAME_CHARS UTF-16 units plus a part envelope, at three UTF-8 bytes each.
        const val MAX_FRAME_BYTES = (LinkLimits.MAX_FRAME_CHARS + 1024) * 3
    }
}
