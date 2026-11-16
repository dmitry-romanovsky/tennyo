package app.tennyo.host.vscode.agents

import app.tennyo.host.engine.agents.AHP_ROOT
import app.tennyo.host.engine.agents.AcpConnection
import app.tennyo.host.engine.agents.AcpException
import app.tennyo.host.engine.agents.AhpHost
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.transport.WebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.js.Promise

/**
 * The connection to VS Code's own agent host (Copilot and the other providers it runs), found through the endpoint
 * files the editor writes under `<userData>/agent-host/local-endpoint/entries`. The editor starts the host; Tennyo
 * only attaches to one that is running, and tries again at most every [RETRY_MILLIS] while none is.
 *
 * Channel states are the protocol package's own objects, advanced by its own reducers, so the engine reads them in
 * the shape the host's version of AHP defines.
 */
class VsCodeAgentHost(private val userDataPath: String, private val scope: CoroutineScope) : AhpHost {
    private var client: dynamic = null
    private var protocol: dynamic = null
    private var connecting = false
    private var lastAttemptMillis = 0L
    private val states = mutableMapOf<String, dynamic>()
    private val readStates = mutableMapOf<String, JsonObject>()
    private val followers = mutableMapOf<String, Job>()

    init {
        attach()
    }

    /** This host while it is connected; otherwise `null`, attaching in the background if a host has appeared. */
    fun connected(): AhpHost? {
        if (client == null) attach()
        return this.takeIf { client != null && states[AHP_ROOT] != null }
    }

    override suspend fun follow(channel: String, onChange: () -> Unit) {
        val live = client ?: throw AcpException(AcpConnection.AGENT_GONE, "The agent host is not connected.")
        val subscribed = (live.subscribe(channel) as Promise<dynamic>).awaitRpc()
        setState(channel, subscribed.result.snapshot?.state)
        val subscription = subscribed.subscription
        onChange()
        followers.remove(channel)?.cancel()
        followers[channel] = scope.launch {
            try {
                var next = (subscription.next() as Promise<dynamic>).await()
                while (next.done != true) {
                    val event = next.value
                    // A rejected action is one the host never applied.
                    if (event.type == "action" && event.params.rejectionReason == null) {
                        setState(channel, reducer(channel)(states[channel], event.params.action))
                        onChange()
                    }
                    next = (subscription.next() as Promise<dynamic>).await()
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                // A dropped socket rejects the next event instead of ending the stream; unfollowing cancels it.
                if (failure is CancellationException) throw failure
                LOG.info("Lost VS Code's agent host on $channel: ${failure.message}")
            }
            // Without the root channel this host is gone: forget it, so the next use attaches afresh.
            if (channel == AHP_ROOT) disconnect()
        }
    }

    override fun unfollow(channel: String) {
        followers.remove(channel)?.cancel()
        states.remove(channel)
        readStates.remove(channel)
        (client?.unsubscribe(channel) as? Promise<dynamic>)?.catch { }
    }

    /** Read once per change: a streaming answer changes the chat per token, but the engine reads it coalesced. */
    override fun state(channel: String): JsonObject? = readStates[channel] ?: readState(channel)

    // Not `states[channel]?.let`: on a `dynamic` value that compiles to a JavaScript method call the state lacks.
    private fun readState(channel: String): JsonObject? {
        val raw: dynamic = states[channel]
        if (raw == null) return null
        return (Json.parseToJsonElement(JSON.stringify(raw)) as? JsonObject)?.also { readStates[channel] = it }
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement {
        val live = client ?: throw AcpException(AcpConnection.AGENT_GONE, "The agent host is not connected.")
        val result = (live.request(method, JSON.parse(params.toString())) as Promise<dynamic>).awaitRpc()
        return if (result == null) JsonNull else Json.parseToJsonElement(JSON.stringify(result))
    }

    override fun dispatch(channel: String, action: JsonObject) {
        client?.dispatch(channel, JSON.parse(action.toString()))
    }

    private fun attach() {
        val now = hostWallClockMillis()
        if (connecting || now - lastAttemptMillis < RETRY_MILLIS) return
        val endpoint = liveEndpoint() ?: return
        connecting = true
        lastAttemptMillis = now
        scope.launch {
            try {
                connect(endpoint)
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                LOG.info("VS Code's agent host did not answer: ${failure.message}")
                disconnect()
            } finally {
                connecting = false
            }
        }
    }

    private suspend fun connect(endpoint: dynamic) {
        protocol = (js("import('@microsoft/agent-host-protocol')") as Promise<dynamic>).await()
        val clients = (js("import('@microsoft/agent-host-protocol/client')") as Promise<dynamic>).await()
        val transports = (js("import('@microsoft/agent-host-protocol/ws')") as Promise<dynamic>).await()
        val socket = openSocket(endpoint)
        val live = js("new clients.AhpClient(transports.WebSocketTransport.fromSocket(socket))")
        live.connect()
        val initialize = jsObject(
            "clientId" to hostRandomUuid(),
            "protocolVersions" to protocol.SUPPORTED_PROTOCOL_VERSIONS,
        )
        (live.initialize(initialize) as Promise<dynamic>).awaitRpc()
        client = live
        follow(AHP_ROOT) {}
        LOG.info("Attached to VS Code's agent host.")
    }

    /** Opens the host's WebSocket: over its named pipe or Unix socket, or on the loopback port it wrote. */
    private suspend fun openSocket(endpoint: dynamic): dynamic {
        val token = js("encodeURIComponent")(endpoint.connectionToken ?: "") as String
        val address = endpoint.endpoint
        // `ws` dials a named pipe or Unix socket through its `ws+unix:<socket>:<request path>` scheme. No `//`:
        // a Windows pipe path after it is not a valid URL.
        val url = if (address.type == "socket") {
            "ws+unix:${address.path}:/?tkn=$token"
        } else {
            "ws://${address.host}:${address.port}/?tkn=$token"
        }
        val socket = WebSocket(url, js("{}")).asDynamic()
        Promise<Unit> { opened, failed ->
            socket.once("open") { opened(Unit) }
            socket.once("error") { error: dynamic -> failed(Error(error?.message as? String ?: "connection failed")) }
        }.await()
        return socket
    }

    /** The first endpoint whose editor process is still alive; entries can outlive a crashed window. */
    private fun liveEndpoint(): dynamic {
        val fs = js("require('node:fs')")
        val path = js("require('node:path')")
        val directory = path.join(userDataPath, "agent-host", "local-endpoint", "entries") as String
        val names = runCatching { fs.readdirSync(directory) as Array<String> }.getOrNull() ?: return null
        return names.filter { it.endsWith(".json") }.firstNotNullOfOrNull { name ->
            runCatching {
                val entry = JSON.parse<dynamic>(fs.readFileSync(path.join(directory, name), "utf8") as String)
                js("process").kill(entry.pid, 0)
                entry
            }.getOrNull()
        }
    }

    private fun reducer(channel: String): (dynamic, dynamic) -> dynamic = when {
        channel.startsWith("ahp-root:") -> protocol.rootReducer
        channel.startsWith("ahp-session:") -> protocol.sessionReducer
        else -> protocol.chatReducer
    }.unsafeCast<(dynamic, dynamic) -> dynamic>()

    private fun setState(channel: String, state: dynamic) {
        states[channel] = state
        readStates.remove(channel)
    }

    private fun disconnect() {
        followers.values.forEach { it.cancel() }
        followers.clear()
        states.clear()
        readStates.clear()
        (client?.shutdown() as? Promise<dynamic>)?.catch { }
        client = null
    }

    /** Awaits a client call, turning the protocol's `RpcError` into the [AcpException] the engine reads codes from. */
    // The code and message are all an RpcError carries.
    @Suppress("SwallowedException")
    private suspend fun Promise<dynamic>.awaitRpc(): dynamic =
        try {
            await()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            val code = failure.asDynamic().code as? Int ?: throw failure
            throw AcpException(code, failure.message.orEmpty())
        }

    private fun jsObject(vararg entries: Pair<String, Any?>): dynamic {
        val options = js("{}")
        entries.forEach { (key, value) -> options[key] = value }
        return options
    }

    private companion object {
        val LOG = HostLogger("VsCodeAgentHost")
        const val RETRY_MILLIS = 10_000L
    }
}
