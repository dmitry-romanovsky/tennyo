package app.tennyo.mobile.link.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * Builds the dedicated, pinned client used only for link traffic; [allowUnsafeTraffic] is as for
 * [pinnedHostnameVerifier].
 */
fun createOkHttpLinkClient(json: Json, allowUnsafeTraffic: Boolean): HttpClient {
    val trustManager = pinnedTrustManager()
    return createLinkHttpClient(OkHttp, json) {
        config {
            dispatcher(okHttpDispatcher())
            connectionPool(okHttpConnectionPool())
            sslSocketFactory(pinnedSslSocketFactory(trustManager), trustManager)
            hostnameVerifier(pinnedHostnameVerifier(allowUnsafeTraffic))
            eventListenerFactory { okHttpTlsEventListener() }
        }
    }
}

internal fun okHttpDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequests = MAX_LINK_REQUESTS
    maxRequestsPerHost = MAX_LINK_REQUESTS
}

internal fun okHttpConnectionPool(): ConnectionPool =
    ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_CONNECTION_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)

internal const val MAX_LINK_REQUESTS = 8
internal const val MAX_IDLE_CONNECTIONS = 8
internal const val IDLE_CONNECTION_KEEP_ALIVE_SECONDS = 15L
