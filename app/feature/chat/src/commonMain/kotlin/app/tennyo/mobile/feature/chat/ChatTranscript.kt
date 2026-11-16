package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatDiff
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatPermissionKind
import app.tennyo.contract.ChatToolKind
import app.tennyo.contract.ChatToolStatus

/**
 * The host's transcript as the pane's blocks. An edit with a reported diff becomes its tool row followed by the diff.
 * [previous] carries what only the phone knows — which thoughts and tool outputs the user opened — onto the fresh
 * blocks by position, since a streaming transcript only ever grows at its end.
 */
internal fun transcriptBlocks(entries: List<ChatEntry>, previous: List<ChatBlock>): List<ChatBlock> {
    val blocks = entries.flatMap(::blocksOf)
    return blocks.mapIndexed { index, block ->
        val before = previous.getOrNull(index)
        when {
            block is ChatBlock.Thinking && before is ChatBlock.Thinking -> block.copy(expanded = before.expanded)
            block is ChatBlock.ToolStep && before is ChatBlock.ToolStep -> block.copy(expanded = before.expanded)
            // The unchanged messages of a streaming answer keep their parsed Markdown.
            block is ChatBlock.Prose && before is ChatBlock.Prose && block.text == before.text -> before
            else -> block
        }
    }
}

private fun blocksOf(entry: ChatEntry): List<ChatBlock> = when (entry) {
    is ChatEntry.Prompt -> listOf(ChatBlock.Prompt(entry.text, entry.attachments))
    is ChatEntry.Plan -> listOf(ChatBlock.Plan(entry.steps))
    is ChatEntry.Form -> listOf(ChatBlock.Form(entry.requestId, entry.message, entry.fields, entry.answer))
    is ChatEntry.Message -> listOf(ChatBlock.Prose(entry.text))
    is ChatEntry.Thought -> listOf(ChatBlock.Thinking(entry.text))
    is ChatEntry.Tool -> listOfNotNull(
        ChatBlock.ToolStep(entry.kind.stepKind, entry.title, entry.status.stepStatus, entry.output),
        entry.diff?.toBlock(),
    )

    is ChatEntry.Permission -> listOf(
        ChatBlock.Permission(
            id = entry.requestId,
            action = entry.title,
            // Allow answers with the once-option when the agent offers one, so the card must not promise less.
            scope = entry.options.map { it.kind }.let { kinds ->
                if (ChatPermissionKind.ALLOW_ONCE !in kinds && ChatPermissionKind.ALLOW_ALWAYS in kinds) {
                    PermissionScope.ALWAYS
                } else {
                    PermissionScope.ONCE
                }
            },
            decision = entry.chosenOptionId?.let { chosen ->
                val kind = entry.options.firstOrNull { it.optionId == chosen }?.kind
                if (kind == ChatPermissionKind.ALLOW_ONCE || kind == ChatPermissionKind.ALLOW_ALWAYS) {
                    PermissionDecision.ALLOWED
                } else {
                    PermissionDecision.DENIED
                }
            },
        ),
    )
}

/**
 * The diff as one removed-then-added hunk: the lines both texts share at their start and end are left out, and
 * everything between them counts as changed.
 */
private fun ChatDiff.toBlock(): ChatBlock.Diff {
    val old = oldText?.lines().orEmpty()
    val new = newText.lines()
    val start = old.zip(new).takeWhile { (before, after) -> before == after }.size
    val end = old.drop(start).asReversed().zip(new.drop(start).asReversed())
        .takeWhile { (before, after) -> before == after }.size
    val hunk = old.subList(start, old.size - end).map { DiffLine(DiffLineKind.REMOVED, it) } +
        new.subList(start, new.size - end).map { DiffLine(DiffLineKind.ADDED, it) }
    return ChatBlock.Diff(
        fileName = path.substringAfterLast('/'),
        addedLines = hunk.count { it.kind == DiffLineKind.ADDED },
        removedLines = hunk.count { it.kind == DiffLineKind.REMOVED },
        hunk = hunk,
    )
}

private val ChatToolKind.stepKind: ToolStepKind
    get() = when (this) {
        ChatToolKind.READ -> ToolStepKind.READ
        ChatToolKind.EDIT -> ToolStepKind.EDIT
        ChatToolKind.SEARCH -> ToolStepKind.SEARCH
        ChatToolKind.EXECUTE, ChatToolKind.OTHER -> ToolStepKind.COMMAND
    }

private val ChatToolStatus.stepStatus: ToolStepStatus
    get() = when (this) {
        ChatToolStatus.RUNNING -> ToolStepStatus.RUNNING
        ChatToolStatus.DONE -> ToolStepStatus.DONE
        ChatToolStatus.FAILED -> ToolStepStatus.FAILED
    }
