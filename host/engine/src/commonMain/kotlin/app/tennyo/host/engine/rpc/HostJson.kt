package app.tennyo.host.engine.rpc

import kotlinx.serialization.json.Json

/**
 * JSON of the public endpoints' bodies, protected payloads and stored remembered pairs. Defaults are written out and
 * the discriminator stays `type`; unknown keys are ignored so a newer peer's optional fields do not break decoding.
 */
val HostJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}

inline fun <reified T> hostJson(value: T): String = HostJson.encodeToString(value)
