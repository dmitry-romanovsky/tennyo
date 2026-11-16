package app.tennyo.host.engine.agents

import app.tennyo.host.engine.platform.HostConcurrentMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicLong

/** An error an ACP peer answered with, or one this side answers a peer's request with. */
class AcpException(val code: Int, message: String) : Exception(message)

/**
 * JSON-RPC 2.0 over newline-delimited JSON, the transport ACP runs on stdio. The host is the client: it sends requests
 * and notifications to the agent, and answers the agent's own requests through [onRequest].
 *
 * ACP is an external protocol that evolves on its own, so messages are read as plain JSON and a field this does not
 * know is ignored, never an error.
 */
class AcpConnection(
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
    private val onNotification: (method: String, params: JsonObject) -> Unit,
    private val onRequest: suspend (method: String, params: JsonObject) -> JsonElement,
) {
    private val nextId = AtomicLong(0L)
    private val pending = HostConcurrentMap<Long, CompletableDeferred<JsonElement>>()

    suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextId.addAndFetch(1L)
        val answer = CompletableDeferred<JsonElement>()
        pending[id] = answer
        try {
            send(message { put("id", id); put("method", method); put("params", params) })
            return answer.await()
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject) {
        send(message { put("method", method); put("params", params) })
    }

    /** Handles one line the agent wrote. A line that is not a JSON-RPC message is ignored, as agents log to stdout. */
    fun receive(line: String) {
        val message = runCatching { AcpJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = message["id"]
        when {
            method != null && id != null && id !is JsonNull -> scope.launch { answer(id, method, params) }
            method != null -> onNotification(method, params)
            else -> (id as? JsonPrimitive)?.longOrNull?.let(pending::remove)?.let { settle(it, message) }
        }
    }

    /** Fails every request still waiting, once the agent is gone. */
    fun close(reason: String) {
        pending.entries().forEach { (id, deferred) ->
            pending.remove(id)
            deferred.completeExceptionally(AcpException(AGENT_GONE, reason))
        }
    }

    private suspend fun answer(id: JsonElement, method: String, params: JsonObject) {
        val reply = try {
            val result = onRequest(method, params)
            message { put("id", id); put("result", result) }
        } catch (failure: AcpException) {
            message { put("id", id); put("error", error(failure.code, failure.message.orEmpty())) }
        }
        // An agent that exited while its request was open cannot take the answer; that is not this side's error.
        runCatching { send(reply) }
    }

    private fun settle(deferred: CompletableDeferred<JsonElement>, message: JsonObject) {
        val error = message["error"] as? JsonObject
        if (error == null) {
            deferred.complete(message["result"] ?: JsonNull)
            return
        }
        val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: INTERNAL_ERROR
        val text = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        deferred.completeExceptionally(AcpException(code, text))
    }

    private fun message(fill: JsonObjectBuilder.() -> Unit): String =
        AcpJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("jsonrpc", "2.0"); fill() })

    private fun error(code: Int, text: String) = buildJsonObject { put("code", code); put("message", text) }

    companion object {
        const val METHOD_NOT_FOUND = -32_601
        const val INTERNAL_ERROR = -32_603

        /** ACP's `auth_required`: the agent needs signing in before a session. */
        const val AUTH_REQUIRED = -32_000

        /** Not a JSON-RPC code: the process ended with the request unanswered. */
        const val AGENT_GONE = -1
    }
}

internal val AcpJson = Json { ignoreUnknownKeys = true }
