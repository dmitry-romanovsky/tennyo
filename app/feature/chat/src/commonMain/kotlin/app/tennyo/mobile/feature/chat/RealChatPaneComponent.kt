package app.tennyo.mobile.feature.chat

import app.tennyo.contract.Capability
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatPermissionKind
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.pane.PaneLink
import app.tennyo.mobile.link.pane.SyncPass
import app.tennyo.mobile.link.pane.WorkspacePane
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import app.tennyo.contract.ChatState as HostChatState

/**
 * The Chat pane over [link]. The host owns the conversation: every call answers with its whole state and every
 * change is pushed, so the pane keeps only what is its own — the draft and which thoughts are open.
 */
class RealChatPaneComponent(
    componentContext: ComponentContext,
    private val link: PaneLink,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : ChatPaneComponent, WorkspacePane, ComponentContext by componentContext {

    private val store = FeatureStore<ChatState, ChatMsg, Nothing>(
        link.project.value.let { project ->
            ChatState(available = Capability.CHAT in project.capabilities, editor = project.editor)
        },
        lifecycle,
        ::reduceChat,
        mainContext,
    )
    private val scope get() = store.scope

    override val state: StateFlow<ChatState> = store.state

    init {
        // The session's snapshot pass may run before the host reports Chat, or before this pane exists at all (a
        // workspace reopened over a live session), so the pane loads its own snapshot until one has arrived.
        link.project
            .map { (Capability.CHAT in it.capabilities) to it.editor }
            .distinctUntilChanged()
            .onEach { (available, editor) ->
                store.dispatch(ChatMsg.AvailabilityChanged(available, editor))
                if (available) refresh()
            }
            .launchIn(scope)
    }

    override fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.StartAgent -> start(
                LinkCall.ChatStart(intent.agentId, intent.sessionId),
                ChatPending(intent.agentId, ChatPendingKind.START),
            )

            is ChatIntent.ToggleHistory -> toggleHistory(intent.agentId)
            ChatIntent.ShowAgents -> showAgents()
            ChatIntent.ReturnToConversation -> store.dispatch(ChatMsg.AgentsShown(false))
            ChatIntent.HideActiveChat -> showAgents()
            ChatIntent.DismissFailure -> store.dispatch(ChatMsg.FailureDismissed)
            is ChatIntent.Authenticate ->
                start(LinkCall.ChatAuthenticate(intent.methodId), ChatPending(null, ChatPendingKind.SIGN_IN))
            is ChatIntent.SetMode -> call(LinkCall.ChatSetMode(intent.modeId))
            is ChatIntent.SetConfig -> call(LinkCall.ChatSetConfig(intent.configId, intent.value))
            is ChatIntent.ComposerChanged -> store.dispatch(ChatMsg.ComposerChanged(intent.text))
            is ChatIntent.CommandPicked -> store.dispatch(ChatMsg.DraftReplaced("/${intent.name} "))
            is ChatIntent.ImagePicked ->
                store.dispatch(ChatMsg.ImageAdded(ChatAttachment.Image(intent.mimeType, intent.base64)))
            is ChatIntent.ImageRemoved -> store.dispatch(ChatMsg.ImageRemoved(intent.index))
            is ChatIntent.FormValueChanged ->
                store.dispatch(ChatMsg.FormValueChanged(intent.requestId, intent.field, intent.value))
            is ChatIntent.FormAnswered -> answerForm(intent.requestId, intent.action)
            ChatIntent.Send -> send()
            ChatIntent.Stop -> call(LinkCall.ChatCancel)
            is ChatIntent.BlockToggled -> store.dispatch(ChatMsg.BlockToggled(intent.index))
            is ChatIntent.PermissionDecided -> answer(intent.id, intent.decision)
        }
    }

    override suspend fun loadSnapshot(shown: Boolean) {
        if (Capability.CHAT !in link.project.value.capabilities) return
        runCatching { link.call(LinkCall.ChatSnapshot) }
            .onSuccess(::accept)
            .onFailure { Logger.w("Loading the conversation failed", it) }
        if (!store.state.value.inConversation) loadRecent()
    }

    override suspend fun sync(pass: SyncPass) {
        if (!store.state.value.loaded || pass.shown && pass.explicitContent) loadSnapshot(shown = true)
    }

    override fun onShown() {
        scope.launch { refresh() }
    }

    /**
     * Loads the snapshot until one has arrived, and the earlier conversations whenever the agent list shows: a push
     * can deliver the snapshot first, and the list must not stay empty because of it.
     */
    private suspend fun refresh() {
        if (Capability.CHAT !in link.project.value.capabilities) return
        when {
            !store.state.value.loaded -> loadSnapshot(shown = true)
            !store.state.value.inConversation -> loadRecent()
        }
    }

    override fun onBack(): Boolean {
        if (!store.state.value.inConversation) return false
        showAgents()
        return true
    }

    private fun showAgents() {
        store.dispatch(ChatMsg.AgentsShown(true))
        scope.launch { loadRecent() }
    }

    /** The conversations listed under the agents; a host that cannot list them now leaves the last list shown. */
    private suspend fun loadRecent() {
        runCatchingCancellable { link.call(LinkCall.ChatHistory) }
            .onSuccess { result ->
                if (result is LinkResult.ChatHistory) store.dispatch(ChatMsg.RecentLoaded(result.entries))
            }
            .onFailure { Logger.w("Listing earlier conversations failed", it) }
    }

    override fun apply(push: LinkPush) {
        if (push is LinkPush.Chat) show(push.state)
    }

    /**
     * A call that answers with the whole conversation, shown as [pending] on the one agent it concerns until it does.
     * A second request waits for the first: the host runs one conversation at a time.
     */
    private fun start(call: LinkCall, pending: ChatPending) {
        if (store.state.value.pending != null) return
        store.dispatch(ChatMsg.PendingChanged(pending))
        scope.launch {
            runCatching { link.call(call) }
                .onSuccess { result ->
                    accept(result)
                    // A started agent's conversation is the one to show, even one picked from the list.
                    if (pending.kind == ChatPendingKind.START) store.dispatch(ChatMsg.AgentsShown(false))
                }
                .onFailure { Logger.w("Chat request ${pending.kind} failed", it) }
            store.dispatch(ChatMsg.PendingChanged(null))
        }
    }

    /** Opens [agentId]'s earlier conversations (asking the host for them), or closes them when already open. */
    private fun toggleHistory(agentId: String) {
        if (store.state.value.historyAgentId == agentId) {
            store.dispatch(ChatMsg.HistoryShown(null))
            return
        }
        store.dispatch(ChatMsg.HistoryShown(agentId))
        start(LinkCall.ChatListSessions(agentId), ChatPending(agentId, ChatPendingKind.HISTORY))
    }

    /** Sends the draft with its pictures and the project files it names as `@path`. */
    private fun send() {
        val state = store.state.value
        val text = state.composerText.trim()
        if (text.isEmpty() && state.pendingImages.isEmpty()) return
        val attachments = state.pendingImages + mentionedFiles(text).map(ChatAttachment::FileLink)
        store.dispatch(ChatMsg.DraftSent)
        scope.launch {
            val sent = runCatchingCancellable { link.call(LinkCall.ChatPrompt(text, attachments)) }
                .onFailure { Logger.w("Sending the prompt failed", it) }
                .getOrNull()
            // A host that is busy or has no conversation open refuses the prompt without failing the call.
            if ((sent as? LinkResult.Done)?.accepted != true) {
                store.dispatch(ChatMsg.DraftReturned(state.composerText, state.pendingImages))
            }
        }
    }

    private fun answerForm(requestId: String, action: ChatFormAction) {
        val values = store.state.value.formDrafts[requestId].orEmpty()
        call(LinkCall.ChatAnswerForm(requestId, action, values))
    }

    /** Allow and deny pick the agent's own once-options first, the narrowest grant each side offers. */
    private fun answer(requestId: String, decision: PermissionDecision) {
        val permission = store.state.value.host.entries.filterIsInstance<ChatEntry.Permission>()
            .firstOrNull { it.requestId == requestId } ?: return
        val wanted = if (decision == PermissionDecision.ALLOWED) ALLOW_KINDS else REJECT_KINDS
        val option = wanted.firstNotNullOfOrNull { kind ->
            permission.options.firstOrNull { it.kind == kind }
        } ?: return
        call(LinkCall.ChatAnswer(requestId, option.optionId))
    }

    private fun call(call: LinkCall) {
        scope.launch {
            runCatchingCancellable { link.call(call) }.onFailure { Logger.w("Chat call failed", it) }
        }
    }

    private fun accept(result: LinkResult) {
        if (result is LinkResult.Chat) show(result.state)
    }

    private fun show(state: HostChatState) {
        store.dispatch(ChatMsg.HostChanged(state))
    }

    private companion object {
        val ALLOW_KINDS = listOf(ChatPermissionKind.ALLOW_ONCE, ChatPermissionKind.ALLOW_ALWAYS)
        val REJECT_KINDS = listOf(ChatPermissionKind.REJECT_ONCE, ChatPermissionKind.REJECT_ALWAYS)
    }
}
