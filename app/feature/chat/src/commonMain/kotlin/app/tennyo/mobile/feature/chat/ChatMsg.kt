package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.HostEditor
import app.tennyo.contract.ChatState as HostChatState

/** Every state change of the Chat pane. */
internal sealed interface ChatMsg {
    data class AvailabilityChanged(val available: Boolean, val editor: HostEditor? = null) : ChatMsg

    /** The host's conversation, from a call's answer or a push. */
    data class HostChanged(val host: HostChatState) : ChatMsg
    data class PendingChanged(val pending: ChatPending?) : ChatMsg
    data class HistoryShown(val agentId: String?) : ChatMsg
    data class AgentsShown(val shown: Boolean) : ChatMsg
    data class RecentLoaded(val recent: List<ChatHistoryEntry>) : ChatMsg
    data object FailureDismissed : ChatMsg
    data class ComposerChanged(val text: String) : ChatMsg

    /** The draft replaced as a whole, as a picked command does. */
    data class DraftReplaced(val text: String) : ChatMsg
    data class ImageAdded(val image: ChatAttachment.Image) : ChatMsg
    data class ImageRemoved(val index: Int) : ChatMsg

    /** The draft and its pictures went out with a prompt. */
    data object DraftSent : ChatMsg

    /** A prompt that could not go out comes back to the composer, unless the user has started another one. */
    data class DraftReturned(val text: String, val images: List<ChatAttachment.Image>) : ChatMsg
    data class BlockToggled(val index: Int) : ChatMsg
    data class FormValueChanged(val requestId: String, val field: String, val value: String) : ChatMsg
}

internal fun reduceChat(state: ChatState, msg: ChatMsg): ChatState = when (msg) {
    is ChatMsg.AvailabilityChanged -> state.copy(available = msg.available, editor = msg.editor)
    is ChatMsg.HostChanged -> state.copy(
        host = msg.host,
        loaded = true,
        blocks = transcriptBlocks(msg.host.entries, state.blocks),
        // A form the host no longer shows as open keeps no draft.
        formDrafts = state.formDrafts.filterKeys { id ->
            msg.host.entries.any { it is ChatEntry.Form && it.requestId == id && it.answer == null }
        },
    )

    // Once a request has answered, the failure the host now reports is news, even when it reads the same.
    is ChatMsg.PendingChanged ->
        state.copy(pending = msg.pending, dismissedFailure = state.dismissedFailure.takeIf { msg.pending != null })

    ChatMsg.FailureDismissed -> state.copy(dismissedFailure = state.failure)
    is ChatMsg.HistoryShown -> state.copy(historyAgentId = msg.agentId)
    is ChatMsg.AgentsShown -> state.copy(browsingAgents = msg.shown)
    is ChatMsg.RecentLoaded -> state.copy(recent = msg.recent)
    is ChatMsg.ComposerChanged -> state.copy(composerText = msg.text)
    is ChatMsg.DraftReplaced -> state.copy(composerText = msg.text, composerRevision = state.composerRevision + 1)
    is ChatMsg.ImageAdded -> state.copy(pendingImages = state.pendingImages + msg.image)
    is ChatMsg.ImageRemoved -> state.copy(pendingImages = state.pendingImages.filterIndexed { i, _ -> i != msg.index })
    ChatMsg.DraftSent -> state.copy(composerText = "", pendingImages = emptyList())
    is ChatMsg.DraftReturned ->
        if (state.composerText.isNotEmpty() || state.pendingImages.isNotEmpty()) {
            state
        } else {
            state.copy(
                composerText = msg.text,
                pendingImages = msg.images,
                composerRevision = state.composerRevision + 1,
            )
        }

    is ChatMsg.BlockToggled -> state.copy(
        blocks = state.blocks.mapIndexed { index, block ->
            when {
                index != msg.index -> block
                block is ChatBlock.Thinking -> block.copy(expanded = !block.expanded)
                block is ChatBlock.ToolStep -> block.copy(expanded = !block.expanded)
                else -> block
            }
        },
    )

    is ChatMsg.FormValueChanged -> {
        val draft = state.formDrafts[msg.requestId].orEmpty() + (msg.field to msg.value)
        state.copy(formDrafts = state.formDrafts + (msg.requestId to draft))
    }
}
