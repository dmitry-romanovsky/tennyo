package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * One kind of agent conversation the phone's Chat pane drives: ACP agents the host launches ([ChatController]), or
 * the sessions of VS Code's own agent host. Every call is the phone's call, answered with the state it should show.
 */
interface ChatBackend {
    /** The agents this backend offers, by an id unique across backends. */
    fun agents(): List<ChatAgent>

    fun snapshot(): ChatState

    suspend fun start(agentId: String, sessionId: String?): ChatState

    suspend fun listSessions(agentId: String): ChatState

    /** Earlier conversations this backend can list without starting an agent, newest first. */
    suspend fun history(): List<ChatHistoryEntry>

    suspend fun authenticate(methodId: String): ChatState

    suspend fun setMode(modeId: String): Boolean

    suspend fun setConfig(configId: String, value: String): Boolean

    fun prompt(text: String, attachments: List<ChatAttachment>): Boolean

    fun cancel(): Boolean

    fun answer(requestId: String, optionId: String?): Boolean

    fun answerForm(requestId: String, action: ChatFormAction, values: Map<String, String>): Boolean

    fun close()
}

/** An agent source beside the ACP agents the host launches; it makes one [ChatBackend] per link session. */
interface ChatSource {
    fun agents(): List<ChatAgent>

    fun backend(scope: CoroutineScope, publish: (ChatState) -> Unit): ChatBackend
}

/**
 * The session's Chat over several backends: the agent picker lists all of theirs, and the one whose agent the phone
 * started owns the selected conversation. Other backends remain alive so switching agents does not stop their work;
 * only the selected backend's changes reach the phone. Calls and the backends' own publishing run on different threads,
 * so the selection and each backend's cancelling flag are atomics.
 */
@Suppress("TooManyFunctions")
class ChatRouter(
    publish: (ChatState) -> Unit,
    backends: List<(publish: (ChatState) -> Unit) -> ChatBackend>,
) : ChatBackend {
    private val active = AtomicReference<ChatBackend?>(null)

    init {
        require(backends.isNotEmpty()) { "A chat router needs at least one backend." }
    }

    private val backends: List<ChatBackend> = backends.map { create ->
        lateinit var backend: ChatBackend
        val cancelling = AtomicBoolean(false)
        backend = create { state ->
            if (backend === activeOrFirst()) {
                publish(state.withAllAgents())
            } else if (state.hasPendingPermission() && cancelling.compareAndSet(false, true)) {
                // A permission request from a backend in the background is cancelled rather than shown on the phone.
                try {
                    backend.cancel()
                } finally {
                    cancelling.store(false)
                }
            }
        }
        backend
    }

    override fun agents(): List<ChatAgent> = backends.flatMap { backend ->
        runCatching { backend.agents() }.getOrElse { emptyList() }
    }

    override fun snapshot(): ChatState = activeOrFirst().snapshot().withAllAgents()

    override suspend fun start(agentId: String, sessionId: String?): ChatState =
        switchTo(agentId).start(agentId, sessionId).withAllAgents()

    override suspend fun listSessions(agentId: String): ChatState =
        switchTo(agentId).listSessions(agentId).withAllAgents()

    /** Every backend's history, newest first; one that cannot list now is left out rather than failing the rest. */
    override suspend fun history(): List<ChatHistoryEntry> = backends
        .flatMap { backend ->
            runCatching { backend.history() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { emptyList() }
        }
        .sortedByDescending { it.updatedAt.orEmpty() }

    override suspend fun authenticate(methodId: String): ChatState =
        activeOrFirst().authenticate(methodId).withAllAgents()

    override suspend fun setMode(modeId: String): Boolean = activeOrFirst().setMode(modeId)

    override suspend fun setConfig(configId: String, value: String): Boolean =
        activeOrFirst().setConfig(configId, value)

    override fun prompt(text: String, attachments: List<ChatAttachment>): Boolean =
        activeOrFirst().prompt(text, attachments)

    override fun cancel(): Boolean = activeOrFirst().cancel()

    override fun answer(requestId: String, optionId: String?): Boolean = activeOrFirst().answer(requestId, optionId)

    override fun answerForm(requestId: String, action: ChatFormAction, values: Map<String, String>): Boolean =
        activeOrFirst().answerForm(requestId, action, values)

    override fun close() = backends.forEach { it.close() }

    /** Selects the backend offering [agentId] without ending conversations owned by other backends. */
    private fun switchTo(agentId: String): ChatBackend {
        val owner = backends.firstOrNull { backend -> backend.agents().any { it.id == agentId } } ?: activeOrFirst()
        val previous = active.load()
        // A permission still pending on the backend being left is cancelled so its turn cannot hang.
        if (previous != null && previous !== owner && previous.snapshot().hasPendingPermission()) previous.cancel()
        active.store(owner)
        return owner
    }

    private fun activeOrFirst(): ChatBackend = active.load() ?: backends.first()

    private fun ChatState.hasPendingPermission(): Boolean =
        entries.any { it is ChatEntry.Permission && it.chosenOptionId == null }

    private fun ChatState.withAllAgents(): ChatState = copy(agents = agents())
}
