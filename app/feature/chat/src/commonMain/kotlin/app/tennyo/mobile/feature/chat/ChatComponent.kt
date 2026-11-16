package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatFailure
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.HostEditor
import kotlinx.coroutines.flow.StateFlow
import app.tennyo.contract.ChatState as HostChatState

/** The Chat destination: a conversation with an ACP agent the host runs, mirrored from the host's transcript. */
interface ChatPaneComponent {
    val state: StateFlow<ChatState>

    fun onIntent(intent: ChatIntent)
}

/**
 * The pane's state: the [host]'s conversation as it last reported it, and what only the phone has — the draft, the
 * pictures picked for it, half-filled forms and which thoughts are open.
 */
data class ChatState(
    /** Whether this host serves the Chat capability at all. */
    val available: Boolean = false,
    /** The editor the host runs in, which words the help shown while Chat is unavailable. */
    val editor: HostEditor? = null,
    val host: HostChatState = HostChatState(),
    /** The host has told the pane what it has — agents, a conversation — at least once. */
    val loaded: Boolean = false,
    /** What the pane asked the host for and is waiting on; one request at a time. */
    val pending: ChatPending? = null,
    /** The agent whose earlier conversations are listed under it, if any. */
    val historyAgentId: String? = null,
    /** Earlier conversations across agents, listed under them, newest first. */
    val recent: List<ChatHistoryEntry> = emptyList(),
    val blocks: List<ChatBlock> = emptyList(),
    val composerText: String = "",
    /** Bumped when the draft is replaced rather than typed, so the focused field takes the new text. */
    val composerRevision: Int = 0,
    /** Pictures to send with the next prompt. */
    val pendingImages: List<ChatAttachment.Image> = emptyList(),
    /** What the user typed into each open form, by request and field. */
    val formDrafts: Map<String, Map<String, String>> = emptyMap(),
    /** The failure the user closed; it stays closed until the pane's next request to the host has answered. */
    val dismissedFailure: ChatFailure? = null,
    /** The user stepped back to the agent list; the host's session stays open until another agent starts. */
    val browsingAgents: Boolean = false,
) {
    val agents: List<ChatAgent> get() = host.agents
    val starting: Boolean get() = pending != null
    val agentId: String? get() = host.agentId
    val busy: Boolean get() = host.busy
    val failure: ChatFailure? get() = host.failure

    /** The host's failure, unless the user closed it. */
    val shownFailure: ChatFailure? get() = failure?.takeIf { it != dismissedFailure }

    /** The host keeps a conversation open that the user stepped away from to the agent list. */
    val hasOpenConversation: Boolean get() = host.sessionId != null && browsingAgents

    /** A conversation is open once the host has a session; before that the pane picks an agent or signs in. */
    val inConversation: Boolean get() = host.sessionId != null && !browsingAgents

    /** The conversation cannot move on while a permission or a form is unresolved. */
    val blockedOn: ChatBlock? get() = blocks.lastOrNull { it.isBlocking() }
}

private fun ChatBlock.isBlocking(): Boolean = when (this) {
    is ChatBlock.Permission -> decision == null
    is ChatBlock.Form -> answer == null
    else -> false
}

sealed interface ChatIntent {
    /** Starts [agentId] on a new session, or resumes its earlier [sessionId]. */
    data class StartAgent(val agentId: String, val sessionId: String? = null) : ChatIntent
    /** Shows, or hides, [agentId]'s earlier conversations under it, listing them from the host when shown. */
    data class ToggleHistory(val agentId: String) : ChatIntent

    /** Leaves the conversation for the agent list. */
    data object ShowAgents : ChatIntent

    /** Goes back into the conversation the host still has open, as it was left. */
    data object ReturnToConversation : ChatIntent
    /** Hides the currently selected conversation from this pane and returns to the agent list. */
    data object HideActiveChat : ChatIntent
    data object DismissFailure : ChatIntent
    data class Authenticate(val methodId: String) : ChatIntent
    data class SetMode(val modeId: String) : ChatIntent
    data class SetConfig(val configId: String, val value: String) : ChatIntent
    data class ComposerChanged(val text: String) : ChatIntent

    /** A slash command picked from the suggestions: the draft becomes `/name `. */
    data class CommandPicked(val name: String) : ChatIntent
    data class ImagePicked(val mimeType: String, val base64: String) : ChatIntent
    data class ImageRemoved(val index: Int) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    /** Opens or closes a thought or a tool step's output. */
    data class BlockToggled(val index: Int) : ChatIntent
    data class PermissionDecided(val id: String, val decision: PermissionDecision) : ChatIntent
    data class FormValueChanged(val requestId: String, val field: String, val value: String) : ChatIntent
    data class FormAnswered(val requestId: String, val action: ChatFormAction) : ChatIntent
}

/** A request the pane is waiting on, for the one agent it concerns (`null` for sign-in). */
data class ChatPending(val agentId: String?, val kind: ChatPendingKind)

enum class ChatPendingKind { START, HISTORY, SIGN_IN }
