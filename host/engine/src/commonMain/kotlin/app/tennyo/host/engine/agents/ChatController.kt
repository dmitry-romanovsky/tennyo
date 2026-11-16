package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatAuthMethod
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFailure
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatPermissionOption
import app.tennyo.contract.ChatState
import app.tennyo.contract.LinkFaultCode
import app.tennyo.host.engine.panes.IdeFilesPort
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.rpc.CallFault
import app.tennyo.host.engine.rpc.resolveExistingProjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Duration.Companion.milliseconds

/**
 * One session's agent conversation, hosted: the host starts the agent the phone picked, acts as its ACP client, and
 * mirrors the conversation into [ChatState].
 *
 * The host advertises no `fs` or `terminal` client capabilities, so the agent — a program the user configured to run
 * on this computer — reads and writes with its own tools, and asks the phone before acting through
 * `session/request_permission` or a form through `elicitation/create`. The phone reaches a file only by naming a
 * project file to attach, which is confined to the project before the agent sees it.
 *
 * Lines from the agent arrive on the platform's reader thread and are handled one at a time in [scope]; state changes
 * are pushed coalesced over [PUSH_COALESCE_MILLIS], because a streaming answer changes the transcript per token.
 */
@Suppress("TooManyFunctions")
class ChatController(
    private val agents: IdeAgents,
    private val files: IdeFilesPort,
    private val scope: CoroutineScope,
    private val publish: (ChatState) -> Unit,
) : ChatBackend {
    private val state = AtomicReference(ChatState())
    private val permissions = HostConcurrentMap<String, CompletableDeferred<String?>>()
    private val forms = HostConcurrentMap<String, PendingForm>()
    private val runningAgent = AtomicReference<RunningAgent?>(null)
    private val push = CoalescedPush(scope, PUSH_COALESCE_MILLIS) { publish(state.load()) }

    override fun snapshot(): ChatState = update { it.copy(agents = agents()) }

    /**
     * Starts the conversation on [agentId] — a new session, or the earlier [sessionId], replayed — ending any other.
     * A failure comes back typed in the state; sign-in leaves [ChatState.authMethods] to pick from.
     */
    override suspend fun start(agentId: String, sessionId: String?): ChatState =
        attempt { openSession(connect(agentId), sessionId) }

    /** Lists [agentId]'s earlier sessions, starting the agent if it is not running. */
    override suspend fun listSessions(agentId: String): ChatState = attempt {
        val agent = connect(agentId)
        if (!state.load().canListSessions) return@attempt state.load()
        val listed = agent.connection.request("session/list", buildJsonObject { put("cwd", projectRoot()) })
        update { it.copy(sessions = sessionSummaries(listed)) }
    }

    /** The running agent's earlier sessions; listing an agent that is not running would mean starting it. */
    override suspend fun history(): List<ChatHistoryEntry> {
        val agent = runningAgent.load()?.takeIf { !it.stopped && state.load().canListSessions } ?: return emptyList()
        val listed = agent.connection.request("session/list", buildJsonObject { put("cwd", projectRoot()) })
        return sessionSummaries(listed).map { ChatHistoryEntry(agent.server.id, it.sessionId, it.title, it.updatedAt) }
    }

    /** Signs in with [methodId] — the agent does it on the computer — and opens the session that was waiting. */
    override suspend fun authenticate(methodId: String): ChatState = attempt {
        val agent = runningAgent.load() ?: throw CallFault(LinkFaultCode.TARGET_MISSING, "No agent is running.")
        agent.connection.request("authenticate", buildJsonObject { put("methodId", methodId) })
        openSession(agent, agent.pendingSessionId)
    }

    override suspend fun setMode(modeId: String): Boolean {
        val (agent, sessionId) = session() ?: return false
        agent.connection.request(
            "session/set_mode",
            buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", modeId)
            },
        )
        update { it.copy(currentModeId = modeId) }
        return true
    }

    override suspend fun setConfig(configId: String, value: String): Boolean {
        val (agent, sessionId) = session() ?: return false
        val option = state.load().configOptions.firstOrNull { it.id == configId } ?: return false
        val answer = agent.connection.request(
            "session/set_config_option",
            buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                if (option.isSwitch) {
                    put("type", "boolean")
                    put("value", value.toBoolean())
                } else {
                    put("value", value)
                }
            },
        ) as? JsonObject
        update { current ->
            val options = answer?.get("configOptions") as? JsonArray
            current.copy(configOptions = options?.let(::configOptions) ?: current.configOptions)
        }
        return true
    }

    /** Sends [text] and [attachments] as the next turn; `false` while a turn is running or no session is open. */
    override fun prompt(text: String, attachments: List<ChatAttachment>): Boolean {
        val (agent, sessionId) = session() ?: return false
        if (state.load().busy || (text.isBlank() && attachments.isEmpty())) return false
        val blocks = promptBlocks(text, attachments)
        val names = attachments.map { if (it is ChatAttachment.FileLink) it.path else IMAGE_ATTACHMENT }
        update { it.copy(busy = true, failure = null, entries = it.entries + ChatEntry.Prompt(text, names)) }
        scope.launch {
            val turn = buildJsonObject {
                put("sessionId", sessionId)
                put("prompt", blocks)
            }
            val failure = runCatching { agent.connection.request("session/prompt", turn) }.exceptionOrNull()
            val turnFailure = failure?.let { ChatFailure(ChatFailureKind.TURN_FAILED, it.message) }
            update { it.copy(busy = false, failure = turnFailure) }
        }
        return true
    }

    override fun cancel(): Boolean {
        val (agent, sessionId) = session() ?: return false
        permissions.keys().forEach { answer(it, optionId = null) }
        forms.keys().forEach { answerForm(it, ChatFormAction.CANCEL, emptyMap()) }
        agent.connection.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
        return true
    }

    /** Answers a pending permission; `null` [optionId] cancels it. `false` when it was already answered. */
    override fun answer(requestId: String, optionId: String?): Boolean {
        val pending = permissions.remove(requestId) ?: return false
        pending.complete(optionId)
        updateEntries { entry ->
            if (entry is ChatEntry.Permission && entry.requestId == requestId) {
                entry.copy(chosenOptionId = optionId ?: CANCELLED_OPTION)
            } else {
                entry
            }
        }
        return true
    }

    /** Answers a pending form; `false` when it was already answered. */
    override fun answerForm(requestId: String, action: ChatFormAction, values: Map<String, String>): Boolean {
        val pending = forms.remove(requestId) ?: return false
        pending.answer.complete(
            buildJsonObject {
                put("action", action.name.lowercase())
                if (action == ChatFormAction.ACCEPT) put("content", formContent(pending.fields, values))
            },
        )
        updateEntries { entry ->
            if (entry is ChatEntry.Form && entry.requestId == requestId) entry.copy(answer = action) else entry
        }
        return true
    }

    override fun close() = stop()

    /** The running agent for [agentId], launched and initialized if it is not the one running already. */
    private suspend fun connect(agentId: String): RunningAgent {
        runningAgent.load()?.takeIf { it.server.id == agentId && !it.stopped }?.let { return it }
        stop()
        val server = agents.servers().firstOrNull { it.id == agentId } ?: error("That agent is no longer configured.")
        update { ChatState(agents = agents(), agentId = agentId) }
        val agent = launch(server, projectRoot())
        runningAgent.store(agent)
        val initialized = agent.answerInTime(
            INITIALIZE_TIMEOUT_MILLIS,
            "initialize",
            buildJsonObject {
                put("protocolVersion", ACP_PROTOCOL_VERSION)
                putJsonObject("clientCapabilities") {
                    putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
                    put("terminal", false)
                }
                putJsonObject("clientInfo") {
                    put("name", "tennyo")
                    put("title", "Tennyo")
                    put("version", ACP_CLIENT_VERSION)
                }
            },
        ) as? JsonObject ?: JsonObject(emptyMap())
        agent.authMethods = authMethods(initialized, server.command)
        update { it.withAgentCapabilities(initialized) }
        return agent
    }

    /** Opens a new session, or loads [sessionId]; the agent replays a loaded session's history as updates. */
    private suspend fun openSession(agent: RunningAgent, sessionId: String?): ChatState {
        update { it.copy(entries = emptyList(), sessionId = null, failure = null, authMethods = emptyList()) }
        val params = buildJsonObject {
            sessionId?.let { put("sessionId", it) }
            put("cwd", projectRoot())
            put("mcpServers", JsonArray(emptyList()))
        }
        val response = try {
            val method = if (sessionId == null) "session/new" else "session/load"
            agent.answerInTime(SESSION_TIMEOUT_MILLIS, method, params) as? JsonObject
        } catch (failure: AcpException) {
            if (failure.code != AcpConnection.AUTH_REQUIRED) throw failure
            agent.pendingSessionId = sessionId
            return update {
                it.copy(
                    authMethods = agent.authMethods,
                    failure = ChatFailure(ChatFailureKind.SIGN_IN_REQUIRED, failure.message),
                )
            }
        }
        val opened = sessionId ?: response?.string("sessionId")
            ?: throw AcpException(AcpConnection.INTERNAL_ERROR, "No session id.")
        agent.sessionId = opened
        return update { it.withSessionSetup(response).copy(sessionId = opened, failure = null) }
    }

    /**
     * [method]'s answer, or a failure once [timeoutMillis] pass: an agent stuck before it answers would otherwise keep
     * the phone's start spinning for good. The first start of an `npx` agent downloads it, hence the long allowance.
     */
    private suspend fun RunningAgent.answerInTime(
        timeoutMillis: Long,
        method: String,
        params: JsonObject,
    ): JsonElement =
        withTimeoutOrNull(timeoutMillis.milliseconds) { connection.request(method, params) }
            ?: throw AcpException(AcpConnection.INTERNAL_ERROR, "${server.name} did not answer $method in time.")

    /** Runs [block], turning a failure into the typed state the phone shows; a start failure ends the agent. */
    private suspend fun attempt(block: suspend () -> ChatState): ChatState =
        try {
            block()
        } catch (fault: CallFault) {
            throw fault
        } catch (cancelled: CancellationException) {
            // A half-started agent would read as running to the next start, which then fails on it.
            stop()
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            LOG.warn("The agent could not start its session: ${failure.message}", failure)
            stop()
            fail(ChatFailureKind.START_FAILED, failure.message)
        }

    private fun promptBlocks(text: String, attachments: List<ChatAttachment>): JsonArray = buildJsonArray {
        if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
        attachments.forEach { attachment ->
            when (attachment) {
                is ChatAttachment.Image -> {
                    if (!state.load().promptCapabilities.image) {
                        throw CallFault(LinkFaultCode.UNSUPPORTED, "This agent does not take images.")
                    }
                    add(buildJsonObject {
                        put("type", "image")
                        put("mimeType", attachment.mimeType)
                        put("data", attachment.data)
                    })
                }

                is ChatAttachment.FileLink -> {
                    // The phone names a project file; anything that resolves outside the project is refused here.
                    val path = files.resolveExistingProjectPath(attachment.path)
                        ?.takeUnless(files::isDirectory)
                        ?: throw CallFault(LinkFaultCode.TARGET_MISSING, "That file is not in the project.")
                    add(buildJsonObject {
                        put("type", "resource_link")
                        put("uri", fileUri(path))
                        put("name", path.substringAfterLast('/'))
                    })
                }
            }
        }
    }

    private fun stop() {
        permissions.keys().forEach { answer(it, optionId = null) }
        forms.keys().forEach { answerForm(it, ChatFormAction.CANCEL, emptyMap()) }
        runningAgent.exchange(null)?.let { agent ->
            agent.stopped = true
            agent.connection.close("The conversation ended.")
            runCatching { agent.process.stop() }
        }
    }

    private fun launch(server: AgentServer, directory: String): RunningAgent {
        val lines = Channel<String>(Channel.UNLIMITED)
        lateinit var agent: RunningAgent
        val connection = AcpConnection(
            scope = scope,
            send = { line -> agent.process.send(line) },
            onNotification = ::onNotification,
            onRequest = ::onRequest,
        )
        val process = agents.launcher.launch(
            server = server,
            workingDirectory = directory,
            onLine = { lines.trySend(it) },
            onExit = { lines.close() },
        )
        agent = RunningAgent(server, process, connection)
        scope.launch {
            for (line in lines) connection.receive(line)
            onExited(agent)
        }
        return agent
    }

    private fun onExited(agent: RunningAgent) {
        agent.connection.close("The agent exited.")
        if (agent.stopped || !runningAgent.compareAndSet(agent, null)) return
        // Nobody can answer what the agent asked any more, so the phone stops offering it.
        permissions.keys().forEach { answer(it, optionId = null) }
        forms.keys().forEach { answerForm(it, ChatFormAction.CANCEL, emptyMap()) }
        update { it.copy(busy = false, failure = ChatFailure(ChatFailureKind.AGENT_EXITED)) }
    }

    private fun onNotification(method: String, params: JsonObject) {
        if (method != "session/update") return
        val change = params.obj("update") ?: return
        update { it.applyUpdate(change) }
    }

    private suspend fun onRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "session/request_permission" -> askPermission(params)
        "elicitation/create" -> askForm(params)
        else -> throw AcpException(AcpConnection.METHOD_NOT_FOUND, "Tennyo does not serve $method.")
    }

    private suspend fun askPermission(params: JsonObject): JsonElement {
        val requestId = hostRandomUuid()
        val answer = CompletableDeferred<String?>()
        permissions[requestId] = answer
        val options = params.array("options").mapNotNull { option ->
            val entry = option as? JsonObject ?: return@mapNotNull null
            val optionId = entry.string("optionId") ?: return@mapNotNull null
            ChatPermissionOption(optionId, entry.string("name") ?: optionId, permissionKind(entry.string("kind")))
        }
        val title = params.obj("toolCall")?.string("title").orEmpty()
        update { it.copy(entries = it.entries + ChatEntry.Permission(requestId, title, options)) }
        val chosen = answer.await()
        return buildJsonObject {
            putJsonObject("outcome") {
                if (chosen == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
                    put("optionId", chosen)
                }
            }
        }
    }

    /** A form the phone fills in. A URL elicitation needs a browser on the phone's side of a link it cannot open. */
    private suspend fun askForm(params: JsonObject): JsonElement {
        if (params.string("mode") != "form") return buildJsonObject { put("action", "decline") }
        val requestId = hostRandomUuid()
        val fields = formFields(params.obj("requestedSchema"))
        val pending = PendingForm(fields, CompletableDeferred())
        forms[requestId] = pending
        val message = params.string("message").orEmpty()
        update { it.copy(entries = it.entries + ChatEntry.Form(requestId, message, fields)) }
        return pending.answer.await()
    }

    private fun session(): Pair<RunningAgent, String>? {
        val agent = runningAgent.load() ?: return null
        return agent.sessionId?.let { agent to it }
    }

    private fun projectRoot(): String = files.rootPath() ?: error("The project has no folder.")

    private fun fail(kind: ChatFailureKind, detail: String?): ChatState =
        update { it.copy(agents = agents(), busy = false, failure = ChatFailure(kind, detail)) }

    private fun updateEntries(transform: (ChatEntry) -> ChatEntry) {
        update { current -> current.copy(entries = current.entries.map(transform)) }
    }

    private fun update(transform: (ChatState) -> ChatState): ChatState {
        val next = state.updateAndFetch(transform)
        schedulePush()
        return next
    }

    private fun schedulePush() = push.schedule()

    override fun agents(): List<ChatAgent> = agents.servers().map { ChatAgent(it.id, it.name, it.installed) }

    private class RunningAgent(val server: AgentServer, val process: AgentProcess, val connection: AcpConnection) {
        var sessionId: String? = null
        var stopped = false
        var authMethods: List<ChatAuthMethod> = emptyList()

        /** The session a sign-in interrupted: `null` for a new one. */
        var pendingSessionId: String? = null
    }

    private class PendingForm(val fields: List<ChatField>, val answer: CompletableDeferred<JsonElement>)

    private companion object {
        val LOG = HostLogger("ChatController")
        const val ACP_PROTOCOL_VERSION = 1

        /** ACP's `clientInfo.version`, which it requires; it names the link protocol, not a build. */
        const val ACP_CLIENT_VERSION = "1"
        const val PUSH_COALESCE_MILLIS = 100L
        const val INITIALIZE_TIMEOUT_MILLIS = 5 * 60_000L
        const val SESSION_TIMEOUT_MILLIS = 2 * 60_000L

        /** Marks a permission nobody chose, so the phone stops offering it. */
        const val CANCELLED_OPTION = ""

        /** How an image attachment reads in the transcript. */
        const val IMAGE_ATTACHMENT = "image"
    }
}

/** A `file:` URI for an absolute, `/`-separated path, a Windows drive path included. */
internal fun fileUri(path: String): String {
    val encoded = path.replace("%", "%25").replace(" ", "%20").replace("#", "%23")
    return if (encoded.startsWith("/")) "file://$encoded" else "file:///$encoded"
}
