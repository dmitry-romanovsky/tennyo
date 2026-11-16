package app.tennyo.mobile.link.transport

import kotlinx.serialization.json.Json

/** The companion protocol decoder. The app and companions share one contract, so values are never coerced. */
fun linkJson(): Json = Json {
    ignoreUnknownKeys = true
}
