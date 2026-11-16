package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatPlanStep

/**
 * One block in the conversation, at one of the three weights the design gives them:
 *
 * - **Content** sits flat on the background — what the user asked, what the agent said, and diffs.
 * - **Machine work** collapses into a quiet row — thinking and tool steps, expandable but never in the way.
 * - **Blocking** becomes a card that has to be resolved before the conversation moves on — a permission request or
 *   a form.
 *
 * The weight is the type, not a flag: the UI branches on which block it has, and nothing reads visible text.
 */
sealed interface ChatBlock {
    /** What the user sent. */
    data class Prompt(val text: String, val attachments: List<String> = emptyList()) : ChatBlock

    /** What the agent said, as Markdown; [markdown] is parsed on first use so a reused block is never parsed twice. */
    data class Prose(val text: String) : ChatBlock {
        val markdown: List<MarkdownBlock> by lazy { parseMarkdown(text) }
    }

    /** An edit the agent made or proposes, as a unified diff with its own line counts. */
    data class Diff(
        val fileName: String,
        val addedLines: Int,
        val removedLines: Int,
        val hunk: List<DiffLine>,
    ) : ChatBlock

    /** The agent's reasoning, collapsed to one row until the user opens it. */
    data class Thinking(val text: String, val expanded: Boolean = false) : ChatBlock

    /** One step the agent took — reading a file, running a command — and how it ended. */
    data class ToolStep(
        val kind: ToolStepKind,
        val label: String,
        val status: ToolStepStatus,
        val detail: String? = null,
        val expanded: Boolean = false,
    ) : ChatBlock

    /** Something the agent may not do without being told, and how wide the grant would be. */
    data class Permission(
        val id: String,
        val action: String,
        val scope: PermissionScope,
        val decision: PermissionDecision? = null,
    ) : ChatBlock

    /** The agent's plan for the turn: what it will do, and how far it got. */
    data class Plan(val steps: List<ChatPlanStep>) : ChatBlock

    /** A few values the agent asks for; blocking until answered. */
    data class Form(
        val id: String,
        val message: String,
        val fields: List<ChatField>,
        val answer: ChatFormAction? = null,
    ) : ChatBlock
}

data class DiffLine(val kind: DiffLineKind, val text: String)

enum class DiffLineKind { ADDED, REMOVED, CONTEXT }

enum class ToolStepKind { READ, EDIT, COMMAND, SEARCH }

enum class ToolStepStatus { RUNNING, DONE, FAILED }

/** How far a granted permission reaches. Stated on the card so the user is not agreeing to more than they think. */
enum class PermissionScope { ONCE, ALWAYS }

enum class PermissionDecision { ALLOWED, DENIED }
