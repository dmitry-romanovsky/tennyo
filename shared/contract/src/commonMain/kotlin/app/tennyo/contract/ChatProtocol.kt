package app.tennyo.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The agent conversation ([Capability.CHAT]). The host runs an ACP agent from the user's local configuration — the
 * phone only ever names one by [ChatAgent.id], never a command line — and mirrors one conversation per session.
 *
 * Every call answers with the whole [ChatState], and every change is pushed as the whole state, coalesced; a phone
 * that missed pushes reloads it with [LinkCall.ChatSnapshot].
 */
@Serializable
data class ChatState(
    val agents: List<ChatAgent> = emptyList(),
    /** The agent the conversation runs on; `null` until the phone starts one. */
    val agentId: String? = null,
    /** A turn is running: the composer waits, and cancel is offered. */
    val busy: Boolean = false,
    val entries: List<ChatEntry> = emptyList(),
    val failure: ChatFailure? = null,
    /** The agent session the transcript belongs to; `null` while the agent runs without one. */
    val sessionId: String? = null,
    /** Earlier sessions the agent listed, newest first, for [LinkCall.ChatStart] to resume. */
    val sessions: List<ChatSessionSummary> = emptyList(),
    val canListSessions: Boolean = false,
    val canLoadSessions: Boolean = false,
    val promptCapabilities: ChatPromptCapabilities = ChatPromptCapabilities(),
    val modes: List<ChatMode> = emptyList(),
    val currentModeId: String? = null,
    val configOptions: List<ChatConfigOption> = emptyList(),
    /** The agent's slash commands; a prompt starting with `/name` runs one. */
    val commands: List<ChatCommand> = emptyList(),
    /** Non-empty while the agent waits for sign-in, which always happens on the computer. */
    val authMethods: List<ChatAuthMethod> = emptyList(),
)

@Serializable
data class ChatAgent(
    val id: String,
    val name: String,
    /** `false` for a registry agent the host has not fetched yet: its first start downloads it. */
    val installed: Boolean = true,
)

@Serializable
data class ChatSessionSummary(val sessionId: String, val title: String? = null, val updatedAt: String? = null)

/** An earlier conversation with [agentId], for [LinkCall.ChatStart] to resume. */
@Serializable
data class ChatHistoryEntry(
    val agentId: String,
    val sessionId: String,
    val title: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ChatPromptCapabilities(val image: Boolean = false, val embeddedContext: Boolean = false)

@Serializable
data class ChatMode(val id: String, val name: String, val description: String? = null)

/** A session setting: a choice among [choices], or a switch when [isSwitch] (values `"true"` and `"false"`). */
@Serializable
data class ChatConfigOption(
    val id: String,
    val name: String,
    val value: String,
    val choices: List<ChatChoice> = emptyList(),
    val isSwitch: Boolean = false,
    val description: String? = null,
)

@Serializable
data class ChatChoice(val value: String, val name: String)

@Serializable
data class ChatCommand(val name: String, val description: String, val inputHint: String? = null)

/**
 * A way to sign in. The agent runs it on the computer: an [agent method][terminalCommand] `null` opens its own sign-in
 * there; a terminal method is a command the user runs in a terminal on the computer, shown to them as is.
 */
@Serializable
data class ChatAuthMethod(
    val id: String,
    val name: String,
    val description: String? = null,
    val terminalCommand: String? = null,
)

/** Something sent with a prompt besides its text. */
@Serializable
sealed interface ChatAttachment {
    /** A picture, base64 encoded; only for an agent whose [ChatPromptCapabilities.image] is set. */
    @Serializable
    @SerialName("image")
    data class Image(val mimeType: String, val data: String) : ChatAttachment

    /** A project file by project-relative path; the host refuses one outside the project. */
    @Serializable
    @SerialName("file")
    data class FileLink(val path: String) : ChatAttachment
}

/** One entry of the transcript, in order. */
@Serializable
sealed interface ChatEntry {
    @Serializable
    @SerialName("prompt")
    data class Prompt(val text: String, val attachments: List<String> = emptyList()) : ChatEntry

    @Serializable
    @SerialName("message")
    data class Message(val text: String) : ChatEntry

    @Serializable
    @SerialName("thought")
    data class Thought(val text: String) : ChatEntry

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val title: String,
        val kind: ChatToolKind,
        val status: ChatToolStatus,
        /** The file an edit touched, with its before and after text, when the agent reported one. */
        val diff: ChatDiff? = null,
        val output: String? = null,
    ) : ChatEntry

    /** The agent asks before acting; [chosenOptionId] is set once someone answered. */
    @Serializable
    @SerialName("permission")
    data class Permission(
        val requestId: String,
        val title: String,
        val options: List<ChatPermissionOption>,
        val chosenOptionId: String? = null,
    ) : ChatEntry

    /** The agent's current plan; each new plan replaces this entry. */
    @Serializable
    @SerialName("plan")
    data class Plan(val steps: List<ChatPlanStep>) : ChatEntry

    /** The agent asks for a few values in a form; [answer] is set once someone answered. */
    @Serializable
    @SerialName("form")
    data class Form(
        val requestId: String,
        val message: String,
        val fields: List<ChatField>,
        val answer: ChatFormAction? = null,
    ) : ChatEntry
}

@Serializable
data class ChatPlanStep(val content: String, val status: ChatPlanStatus, val priority: ChatPlanPriority)

@Serializable
enum class ChatPlanStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,
}

@Serializable
enum class ChatPlanPriority {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,
}

/** One value a [ChatEntry.Form] asks for; [choices] is set for the choice kinds. */
@Serializable
data class ChatField(
    val name: String,
    val title: String,
    val kind: ChatFieldKind,
    val required: Boolean = false,
    val description: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val default: String? = null,
)

@Serializable
enum class ChatFieldKind {
    @SerialName("text")
    TEXT,

    @SerialName("number")
    NUMBER,

    @SerialName("integer")
    INTEGER,

    @SerialName("switch")
    SWITCH,

    @SerialName("choice")
    CHOICE,

    @SerialName("multi_choice")
    MULTI_CHOICE,
}

@Serializable
enum class ChatFormAction {
    @SerialName("accept")
    ACCEPT,

    @SerialName("decline")
    DECLINE,

    @SerialName("cancel")
    CANCEL,
}

@Serializable
data class ChatDiff(val path: String, val oldText: String?, val newText: String)

@Serializable
data class ChatPermissionOption(val optionId: String, val name: String, val kind: ChatPermissionKind)

@Serializable
enum class ChatPermissionKind {
    @SerialName("allow_once")
    ALLOW_ONCE,

    @SerialName("allow_always")
    ALLOW_ALWAYS,

    @SerialName("reject_once")
    REJECT_ONCE,

    @SerialName("reject_always")
    REJECT_ALWAYS,
}

@Serializable
enum class ChatToolKind {
    @SerialName("read")
    READ,

    @SerialName("edit")
    EDIT,

    @SerialName("execute")
    EXECUTE,

    @SerialName("search")
    SEARCH,

    @SerialName("other")
    OTHER,
}

@Serializable
enum class ChatToolStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,
}

/** Why the conversation cannot go on; the phone words it, [detail] is the agent's own text for "show details". */
@Serializable
data class ChatFailure(val kind: ChatFailureKind, val detail: String? = null)

@Serializable
enum class ChatFailureKind {
    /** The agent's command could not be started. */
    @SerialName("start_failed")
    START_FAILED,

    /** The agent needs signing in, which happens on the computer. */
    @SerialName("sign_in_required")
    SIGN_IN_REQUIRED,

    /** The agent process ended. */
    @SerialName("agent_exited")
    AGENT_EXITED,

    /** The agent answered a prompt with an error. */
    @SerialName("turn_failed")
    TURN_FAILED,
}
