package app.tennyo.mobile.link.transport

import app.tennyo.mobile.link.discoveryDebug
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID

/** Debug-only transport metadata for pinned Companion connections; no request data is logged. */
internal fun okHttpTlsEventListener(): EventListener = OkHttpTlsEventListener()

private class OkHttpTlsEventListener : EventListener() {
    private val correlationId = UUID.randomUUID().toString().take(8)

    override fun connectionAcquired(call: Call, connection: Connection) {
        log(call, "connection-acquired protocol=${connection.protocol()}")
    }

    override fun secureConnectStart(call: Call) {
        log(call, "tls-start")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        log(
            call,
            "tls-end protocol=${handshake?.tlsVersion?.javaName.orEmpty()} " +
                "cipher=${handshake?.cipherSuite?.javaName.orEmpty()}"
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: okhttp3.Protocol?,
        ioe: IOException,
    ) {
        log(call, "connection-failed failure=${ioe.javaClass.simpleName}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log(call, "call-failed failure=${ioe.javaClass.simpleName}")
    }

    private fun log(call: Call, event: String) {
        val url = call.request().url
        if (!isPinnedTransportHost(url.host)) return
        discoveryDebug {
            "remote okhttp transport correlation=$correlationId host=${url.host}:${url.port} $event"
        }
    }
}
