package app.tennyo.mobile.link.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The dedicated client for host traffic. Each platform passes its engine and pins the host's certificate in
 * [configureEngine]; everything above the engine is the same on every platform.
 */
fun <T : HttpClientEngineConfig> createLinkHttpClient(
    engine: HttpClientEngineFactory<T>,
    json: Json,
    configureEngine: T.() -> Unit,
): HttpClient =
    HttpClient(engine) {
        engine(configureEngine)
        install(ContentNegotiation) {
            json(json)
        }
        installLinkErrorHandling(json)
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = LONG_POLL_TIMEOUT_MILLIS
            socketTimeoutMillis = LONG_POLL_TIMEOUT_MILLIS
        }
        install(WebSockets)
    }

private const val CONNECT_TIMEOUT_MILLIS = 15_000L

/** Above the host's longest held request, so a waiting call is not cut off client-side first. */
private const val LONG_POLL_TIMEOUT_MILLIS = 130_000L
