package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatFailure
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.HostEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.tennyo.contract.ChatState as HostChatState

/**
 * The Chat pane has no client yet, so what is under test is the state it keeps: whether the destination is served
 * at all, and how each block type reduces.
 */
class ChatStateTest {

    @Test
    fun `a host that does not serve chat leaves the pane unavailable`() {
        assertFalse(ChatState().available)
    }

    @Test
    fun `availability follows the capability the session reports`() {
        val state = reduceChat(ChatState(), ChatMsg.AvailabilityChanged(available = true))

        assertTrue(state.available)
    }

    @Test
    fun `the editor the session reports reaches the state that words the unavailable help`() {
        val state = reduceChat(ChatState(), ChatMsg.AvailabilityChanged(available = false, editor = HostEditor.CURSOR))

        assertEquals(HostEditor.CURSOR, state.editor)
    }

    @Test
    fun `a dismissed failure stays hidden until the next request has answered`() {
        val failure = ChatFailure(ChatFailureKind.START_FAILED, "npx exited")
        val failed = reduceChat(ChatState(), ChatMsg.HostChanged(HostChatState(failure = failure)))
        assertEquals(failure, failed.shownFailure)

        val dismissed = reduceChat(failed, ChatMsg.FailureDismissed)
        assertNull(dismissed.shownFailure)
        assertNull(reduceChat(dismissed, ChatMsg.HostChanged(HostChatState(failure = failure))).shownFailure)

        // While a retry runs the old failure stays closed; once it has answered, what the host says is news again.
        val retrying = reduceChat(dismissed, ChatMsg.PendingChanged(ChatPending("a", ChatPendingKind.START)))
        assertNull(retrying.shownFailure)
        assertEquals(failure, reduceChat(retrying, ChatMsg.PendingChanged(null)).shownFailure)
    }

    @Test
    fun `thinking opens and closes without touching its neighbours`() {
        val start = ChatState(
            blocks = listOf(
                ChatBlock.Prose("before"),
                ChatBlock.Thinking("reasoning"),
                ChatBlock.Prose("after"),
            ),
        )

        val opened = reduceChat(start, ChatMsg.BlockToggled(1))

        assertTrue(assertIs<ChatBlock.Thinking>(opened.blocks[1]).expanded)
        assertEquals(start.blocks[0], opened.blocks[0])
        assertEquals(start.blocks[2], opened.blocks[2])
        assertFalse(assertIs<ChatBlock.Thinking>(reduceChat(opened, ChatMsg.BlockToggled(1)).blocks[1]).expanded)
    }

    @Test
    fun `an undecided permission blocks the conversation`() {
        val permission = ChatBlock.Permission(id = "p1", action = "Run a command?", scope = PermissionScope.ONCE)

        assertEquals(permission, ChatState(blocks = listOf(permission)).blockedOn)
        assertNull(ChatState(blocks = listOf(permission.copy(decision = PermissionDecision.DENIED))).blockedOn)
    }

    @Test
    fun `content blocks never block the conversation`() {
        val state = ChatState(
            blocks = listOf(
                ChatBlock.Prompt("ask"),
                ChatBlock.Prose("answer"),
                ChatBlock.Diff("A.kt", 1, 0, listOf(DiffLine(DiffLineKind.ADDED, "line"))),
                ChatBlock.ToolStep(ToolStepKind.COMMAND, "./gradlew test", ToolStepStatus.RUNNING),
                ChatBlock.Thinking("reasoning"),
            ),
        )

        assertNull(state.blockedOn)
    }

    @Test
    fun `a replaced draft advances revision while typing keeps it`() {
        val typed = reduceChat(ChatState(), ChatMsg.ComposerChanged("/re"))
        assertEquals(0, typed.composerRevision)

        val replaced = reduceChat(typed, ChatMsg.DraftReplaced("/review "))

        assertEquals("/review ", replaced.composerText)
        assertEquals(1, replaced.composerRevision)
    }
}
