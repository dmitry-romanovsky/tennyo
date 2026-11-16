package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatAuthMethod
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFailure
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatState
import app.tennyo.contract.LinkFaultCode
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.rpc.CallFault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The adapter's half of a connection to VS Code's agent host: the JSON-RPC calls, client actions, and each followed
 * channel's state as the protocol package's own reducers keep it. Failed calls throw [AcpException] with the code.
 */
interface AhpHost {
    /** Subscribes to [channel]; [onChange] runs after its state first arrives and after every action on it. */
    suspend fun follow(channel: String, onChange: () -> Unit)

    fun unfollow(channel: String)

    /** The followed [channel]'s current state, or `null` before it arrives. */
    fun state(channel: String): JsonObject?

    suspend fun request(method: String, params: JsonObject): JsonElement

    fun dispatch(channel: String, action: JsonObject)
}

/** VS Code's agent host as a Chat source; [host] is `null` while no agent host runs. */
class AhpChatSource(private val workingDirectory: String, private val host: () -> AhpHost?) : ChatSource {
    override fun agents(): List<ChatAgent> = host()?.state(AHP_ROOT)?.let(::ahpAgents).orEmpty()

    override fun backend(scope: CoroutineScope, publish: (ChatState) -> Unit): ChatBackend =
        AhpChatBackend(this, workingDirectory, host, scope, publish)
}

/**
 * One link session's conversation with an agent-host session. The host owns the transcript; this follows the
 * session's default chat and reads its state into [ChatState], coalesced like [ChatController]'s pushes.
 * Sign-in happens in VS Code, so a sign-in failure offers only a retry.
 */
@Suppress("TooManyFunctions")
internal class AhpChatBackend(
    private val source: AhpChatSource,
    private val workingDirectory: String,
    private val host: () -> AhpHost?,
    private val scope: CoroutineScope,
    private val publish: (ChatState) -> Unit,
) : ChatBackend {
    private val state = AtomicReference(ChatState())
    private val followed = mutableListOf<String>()
    private var chatUri: String? = null
    private val push = CoalescedPush(scope, PUSH_COALESCE_MILLIS) {
        publish(state.updateAndFetch(::read).copy(agents = agents()))
    }

    override fun agents(): List<ChatAgent> = source.agents()

    override fun snapshot(): ChatState = state.updateAndFetch(::read).copy(agents = agents())

    override suspend fun start(agentId: String, sessionId: String?): ChatState {
        close()
        state.store(ChatState(agentId = agentId, canListSessions = true, canLoadSessions = true))
        val host = host() ?: return fail(ChatFailureKind.START_FAILED, "VS Code's agent host is not running.")
        return try {
            val session = sessionId ?: createSession(host, agentId.removePrefix(AHP_AGENT_PREFIX))
            val chat = defaultChat(host, session)
            chatUri = chat
            follow(host, chat) { schedulePush() }
            update { it.copy(sessionId = session) }
        } catch (failure: AcpException) {
            if (failure.code == AUTH_REQUIRED) {
                update {
                    it.copy(
                        authMethods = listOf(SIGN_IN_IN_VS_CODE),
                        failure = ChatFailure(ChatFailureKind.SIGN_IN_REQUIRED, failure.message),
                    )
                }
            } else {
                close()
                fail(ChatFailureKind.START_FAILED, failure.message)
            }
        }
    }

    override suspend fun listSessions(agentId: String): ChatState {
        val host = host() ?: return fail(ChatFailureKind.START_FAILED, "VS Code's agent host is not running.")
        val listed = host.request("listSessions", buildJsonObject { put("channel", AHP_ROOT) }) as? JsonObject
        val sessions = listed?.let { ahpSessions(it, agentId.removePrefix(AHP_AGENT_PREFIX)) }.orEmpty()
        return update {
            it.copy(agentId = agentId, sessions = sessions, canListSessions = true, canLoadSessions = true)
        }
    }

    override suspend fun history(): List<ChatHistoryEntry> {
        val host = host() ?: return emptyList()
        val listed = host.request("listSessions", buildJsonObject { put("channel", AHP_ROOT) }) as? JsonObject
        val offered = agents().map { it.id }.toSet()
        return listed?.let(::ahpHistory).orEmpty().filter { it.agentId in offered }
    }

    /** Signing in happens in VS Code; this retries the start it interrupted. */
    override suspend fun authenticate(methodId: String): ChatState {
        val agentId = state.load().agentId ?: throw CallFault(LinkFaultCode.TARGET_MISSING, "No agent was started.")
        return start(agentId, state.load().sessionId)
    }

    /** AHP models the mode as the session config's `mode` setting. */
    override suspend fun setMode(modeId: String): Boolean = setConfig(AHP_MODE_KEY, modeId)

    /** Changes one session setting with `session/configChanged`; the host's echo of it updates the state. */
    override suspend fun setConfig(configId: String, value: String): Boolean {
        val session = state.load().sessionId ?: return false
        val host = host() ?: return false
        val current = host.state(session) ?: return false
        val property = ahpConfigProperties(current)[configId] ?: return false
        val typed = if (property.string("type") == "boolean") {
            JsonPrimitive(value.toBooleanStrictOrNull() ?: return false)
        } else {
            if (value !in property.strings("enum")) return false
            JsonPrimitive(value)
        }
        host.dispatch(
            session,
            buildJsonObject {
                put("type", "session/configChanged")
                put("config", buildJsonObject { put(configId, typed) })
            },
        )
        return true
    }

    override fun prompt(text: String, attachments: List<ChatAttachment>): Boolean {
        if (attachments.isNotEmpty()) throw CallFault(LinkFaultCode.UNSUPPORTED, "The agent host takes text only.")
        val chat = chatUri?.takeIf { !state.load().busy && text.isNotBlank() }
        val host = host()
        if (chat == null || host == null) return false
        host.dispatch(
            chat,
            buildJsonObject {
                put("type", "chat/turnStarted")
                put("turnId", hostRandomUuid())
                put("startedAt", Clock.System.now().toString())
                putJsonObject("message") {
                    put("text", text)
                    putJsonObject("origin") { put("kind", "user") }
                }
            },
        )
        update { it.copy(busy = true, failure = null, entries = it.entries + ChatEntry.Prompt(text)) }
        return true
    }

    override fun cancel(): Boolean {
        val chat = chatUri ?: return false
        val turnId = host()?.state(chat)?.obj("activeTurn")?.string("id") ?: return false
        host()?.dispatch(
            chat,
            buildJsonObject {
                put("type", "chat/turnCancelled")
                put("turnId", turnId)
                put("duration", 0)
            },
        )
        return true
    }

    /** Approves or denies the tool call [requestId] names; `false` once it no longer waits. */
    override fun answer(requestId: String, optionId: String?): Boolean {
        val chat = chatUri ?: return false
        val current = host()?.state(chat) ?: return false
        if (ahpEntries(current).none { it is ChatEntry.Permission && it.requestId == requestId }) return false
        val turnId = ahpTurnOf(current, requestId) ?: return false
        host()?.dispatch(
            chat,
            buildJsonObject {
                put("type", "chat/toolCallConfirmed")
                put("turnId", turnId)
                put("toolCallId", requestId)
                if (optionId == ALLOW_OPTION) {
                    put("approved", true)
                    put("confirmed", "user-action")
                } else {
                    put("approved", false)
                    put("reason", "denied")
                }
            },
        )
        return true
    }

    override fun answerForm(requestId: String, action: ChatFormAction, values: Map<String, String>): Boolean {
        val chat = chatUri ?: return false
        val form = host()?.state(chat)?.let(::ahpEntries)
            ?.firstOrNull { it is ChatEntry.Form && it.requestId == requestId && it.answer == null } as? ChatEntry.Form
            ?: return false
        host()?.dispatch(
            chat,
            buildJsonObject {
                put("type", "chat/inputCompleted")
                put("requestId", requestId)
                put("response", action.name.lowercase())
                if (action == ChatFormAction.ACCEPT) put("answers", ahpAnswers(form.fields, values))
            },
        )
        return true
    }

    override fun close() {
        host()?.let { host -> followed.forEach(host::unfollow) }
        followed.clear()
        chatUri = null
    }

    private suspend fun createSession(host: AhpHost, provider: String): String {
        val session = "ahp-session:/${hostRandomUuid()}"
        host.request(
            "createSession",
            buildJsonObject {
                put("channel", session)
                put("provider", provider)
                put("workingDirectories", buildJsonArray { add(JsonPrimitive(fileUri(workingDirectory))) })
            },
        )
        return session
    }

    /**
     * Follows [session] until the host names its chat, and returns it. VS Code creates a session provisionally and
     * marks it ready only once its first turn starts, so the chat is usable while the session is still creating.
     */
    private suspend fun defaultChat(host: AhpHost, session: String): String {
        val opened = CompletableDeferred<String>()
        follow(host, session) {
            schedulePush()
            val current = host.state(session) ?: return@follow
            if (current.string("lifecycle") == "failed") {
                opened.completeExceptionally(
                    AcpException(
                        AcpConnection.INTERNAL_ERROR,
                        current.obj("creationError")?.string("message").orEmpty(),
                    ),
                )
            } else {
                current.string("defaultChat")?.let(opened::complete)
            }
        }
        // Not withTimeout: its exception is a cancellation, which would leave the phone's call unanswered.
        return withTimeoutOrNull(SESSION_OPEN_TIMEOUT) { opened.await() }
            ?: throw AcpException(AcpConnection.INTERNAL_ERROR, "VS Code's agent host did not open the chat.")
    }

    private suspend fun follow(host: AhpHost, channel: String, onChange: () -> Unit) {
        followed += channel
        host.follow(channel, onChange)
    }

    private fun read(current: ChatState): ChatState {
        val chat = chatUri?.let { host()?.state(it) } ?: return current
        val withChat = current.copy(entries = ahpEntries(chat), busy = ahpBusy(chat))
        return current.sessionId?.let { host()?.state(it) }?.let(withChat::withAhpConfig) ?: withChat
    }

    private fun fail(kind: ChatFailureKind, detail: String?): ChatState =
        update { it.copy(busy = false, failure = ChatFailure(kind, detail)) }

    private fun update(transform: (ChatState) -> ChatState): ChatState {
        val next = state.updateAndFetch { read(transform(it)) }
        schedulePush()
        return next.copy(agents = agents())
    }

    private fun schedulePush() = push.schedule()

    private companion object {
        const val PUSH_COALESCE_MILLIS = 100L
        val SESSION_OPEN_TIMEOUT = 30.seconds

        /** AHP's `AuthRequired`: the provider needs its account, which VS Code signs in to. */
        const val AUTH_REQUIRED = -32_007

        val SIGN_IN_IN_VS_CODE = ChatAuthMethod(
            id = "vscode",
            name = "VS Code",
            description = "Sign in from VS Code's Accounts menu, then try again.",
        )
    }
}

/** The agent host's root channel, which lists its providers. */
const val AHP_ROOT = "ahp-root://"
