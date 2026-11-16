package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatDiff
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatPermissionKind
import app.tennyo.contract.ChatPermissionOption
import app.tennyo.contract.ChatToolKind
import app.tennyo.contract.ChatToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import app.tennyo.contract.ChatState as HostChatState

/** The host's transcript, as the pane's blocks. */
class ChatTranscriptTest {
    private val options = listOf(
        ChatPermissionOption("yes", "Allow", ChatPermissionKind.ALLOW_ONCE),
        ChatPermissionOption("no", "Reject", ChatPermissionKind.REJECT_ONCE),
    )

    @Test
    fun `entries map to blocks at their weight`() {
        val blocks = transcriptBlocks(
            listOf(
                ChatEntry.Prompt("Fix it"),
                ChatEntry.Thought("looking"),
                ChatEntry.Message("Done"),
                ChatEntry.Tool(
                    toolCallId = "t1",
                    title = "Edit a.txt",
                    kind = ChatToolKind.EDIT,
                    status = ChatToolStatus.DONE,
                    diff = ChatDiff("a.txt", "teh\nend", "the\nend"),
                ),
                ChatEntry.Permission("p1", "Run tests", options),
            ),
            previous = emptyList(),
        )

        assertEquals(ChatBlock.Prompt("Fix it"), blocks[0])
        assertIs<ChatBlock.Thinking>(blocks[1])
        assertEquals(ChatBlock.Prose("Done"), blocks[2])
        assertEquals(ChatBlock.ToolStep(ToolStepKind.EDIT, "Edit a.txt", ToolStepStatus.DONE), blocks[3])
        val diff = assertIs<ChatBlock.Diff>(blocks[4])
        assertEquals(1 to 1, diff.addedLines to diff.removedLines)
        val permission = assertIs<ChatBlock.Permission>(blocks[5])
        assertEquals(null, permission.decision)
    }

    @Test
    fun `a diff counts a removed line even when the same text stays elsewhere`() {
        val tool = ChatEntry.Tool(
            toolCallId = "t1",
            title = "Edit a.txt",
            kind = ChatToolKind.EDIT,
            status = ChatToolStatus.DONE,
            diff = ChatDiff("a.txt", "}\n}\nend", "}\nend"),
        )

        val diff = assertIs<ChatBlock.Diff>(transcriptBlocks(listOf(tool), emptyList())[1])

        assertEquals(0 to 1, diff.addedLines to diff.removedLines)
    }

    @Test
    fun `an answered permission shows its decision by the option's kind`() {
        val blocks = transcriptBlocks(listOf(ChatEntry.Permission("p1", "Run", options, chosenOptionId = "no")), emptyList())

        assertEquals(PermissionDecision.DENIED, assertIs<ChatBlock.Permission>(blocks.single()).decision)
    }

    @Test
    fun `a permission states the grant allow would give`() {
        val alwaysOnly = listOf(
            ChatPermissionOption("always", "Always allow", ChatPermissionKind.ALLOW_ALWAYS),
            ChatPermissionOption("no", "Reject", ChatPermissionKind.REJECT_ONCE),
        )

        val blocks = transcriptBlocks(
            listOf(ChatEntry.Permission("p1", "Run", options), ChatEntry.Permission("p2", "Run", alwaysOnly)),
            emptyList(),
        )

        assertEquals(PermissionScope.ONCE, assertIs<ChatBlock.Permission>(blocks[0]).scope)
        assertEquals(PermissionScope.ALWAYS, assertIs<ChatBlock.Permission>(blocks[1]).scope)
    }

    @Test
    fun `an opened thought stays open when the transcript streams on`() {
        val first = transcriptBlocks(listOf(ChatEntry.Thought("a")), emptyList())
        val opened = reduceChat(ChatState(blocks = first), ChatMsg.BlockToggled(0)).blocks

        val streamed = transcriptBlocks(listOf(ChatEntry.Thought("ab"), ChatEntry.Message("x")), opened)

        assertTrue(assertIs<ChatBlock.Thinking>(streamed[0]).expanded)
    }

    @Test
    fun `the host state replaces agents and turn and transcript`() {
        val state = reduceChat(
            ChatState(available = true, composerText = "draft"),
            ChatMsg.HostChanged(HostChatState(agentId = "a", busy = true, entries = listOf(ChatEntry.Prompt("hi")))),
        )

        assertEquals("a", state.agentId)
        assertTrue(state.busy)
        assertEquals("draft", state.composerText)
        assertEquals(listOf<ChatBlock>(ChatBlock.Prompt("hi")), state.blocks)
    }
}
