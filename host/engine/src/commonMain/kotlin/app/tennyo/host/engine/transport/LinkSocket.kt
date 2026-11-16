package app.tennyo.host.engine.transport

/** One upgraded WebSocket as its listener exposes it; the protocol engine never sees a socket type. */
interface LinkSocket {
    /**
     * Queues [text] as one text message. Returns `false` when the send has already failed. [onSendFailed] runs for
     * every failed send, whether it failed at once or after the message was queued.
     */
    fun sendText(text: String, onSendFailed: (Throwable?) -> Unit): Boolean

    fun close(code: Int, reason: String)
}
