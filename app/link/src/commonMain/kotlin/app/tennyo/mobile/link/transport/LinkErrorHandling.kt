package app.tennyo.mobile.link.transport

import app.tennyo.contract.ErrorResponse
import app.tennyo.mobile.link.LinkFault
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/** Turns every host error response into a [LinkFault.Host], with the host's code and explanation when it sent them. */
fun HttpClientConfig<*>.installLinkErrorHandling(json: Json) {
    HttpResponseValidator {
        validateResponse { response ->
            val status = response.status.value
            if (status in HttpStatusCode.OK.value until HttpStatusCode.MultipleChoices.value ||
                status == HttpStatusCode.SwitchingProtocols.value ||
                status == HttpStatusCode.NotModified.value
            ) {
                return@validateResponse
            }
            val bodyText = response.bodyAsText()
            val error = runCatching { json.decodeFromString<ErrorResponse>(bodyText) }.getOrNull()
            throw LinkFault.fromHost(
                status = status,
                code = error?.error ?: "http_$status",
                message = error?.message ?: bodyText.ifBlank { response.status.description },
            )
        }
    }
}
