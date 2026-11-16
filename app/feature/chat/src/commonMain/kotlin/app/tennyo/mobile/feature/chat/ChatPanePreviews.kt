package app.tennyo.mobile.feature.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatSessionSummary
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import app.tennyo.contract.ChatState as HostChatState

/** One preview per block weight, in both themes. */
private val CONVERSATION = listOf(
    ChatBlock.Prompt("Find out why the 404 test flakes."),
    ChatBlock.Thinking(
        text = "The failing assertion expects a 404, but delete() throws when the row is already gone.",
    ),
    ChatBlock.ToolStep(
        kind = ToolStepKind.READ,
        label = "Read UserService.kt",
        status = ToolStepStatus.DONE,
    ),
    ChatBlock.Prose(
        """
        ## Root cause
        `delete()` calls `repository.delete()` without checking existence, so **JPA raises**.

        - [x] reproduce the flake
        - [ ] make the delete idempotent
          - keep the `404` for unknown ids

        ```kotlin
        repository.findById(id) ?: return false
        ```

        | Case | Status |
        |:--|--:|
        | row exists | 204 |
        | row is gone | 404 |
        """.trimIndent(),
    ),
    ChatBlock.Diff(
        fileName = "UserService.kt",
        addedLines = 5,
        removedLines = 2,
        hunk = listOf(
            DiffLine(DiffLineKind.CONTEXT, "fun delete(id: UserId): Boolean {"),
            DiffLine(DiffLineKind.REMOVED, "    repository.delete(id)"),
            DiffLine(DiffLineKind.ADDED, "    val row = repository.findById(id) ?: return false"),
            DiffLine(DiffLineKind.ADDED, "    repository.delete(row)"),
        ),
    ),
)

private val PERMISSION = ChatBlock.Permission(
    id = "launch-tests",
    action = "Run a command?",
    scope = PermissionScope.ONCE,
)

/** Thinking, a tool step, Markdown and a diff. */
@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ChatPaneConversationPreview() {
    TennyoTheme {
        ChatConversationScreen(
            component = PreviewChatComponent(
                ChatState(available = true, host = PreviewHost, blocks = CONVERSATION),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ChatPaneConversationDarkPreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark) {
        ChatConversationScreen(
            component = PreviewChatComponent(
                ChatState(available = true, host = PreviewHost, blocks = CONVERSATION),
            ),
        )
    }
}

/** The reasoning opened. */
@Preview(showBackground = true, widthDp = 412, heightDp = 400)
@Composable
private fun ChatPaneThinkingExpandedPreview() {
    TennyoTheme {
        ChatConversationScreen(
            component = PreviewChatComponent(
                ChatState(
                    available = true,
                    host = HostChatState(sessionId = "preview"),
                    blocks = listOf((CONVERSATION[1] as ChatBlock.Thinking).copy(expanded = true)),
                ),
            ),
        )
    }
}

/** A permission, stating how far the grant reaches. */
@Preview(showBackground = true, widthDp = 412, heightDp = 600)
@Composable
private fun ChatPanePermissionPreview() {
    TennyoTheme {
        ChatConversationScreen(
            component = PreviewChatComponent(
                ChatState(available = true, host = PreviewHost, blocks = listOf(PERMISSION)),
            ),
        )
    }
}

/** N4: a host that serves no agent. */
@Preview(showBackground = true, widthDp = 412, heightDp = 400)
@Composable
private fun ChatPaneUnavailablePreview() {
    TennyoTheme {
        ChatPane(
            component = PreviewChatComponent(
                ChatState(available = false),
            ),
            panePadding = PreviewPanePadding,
        )
    }
}

private class PreviewChatComponent(initialState: ChatState) : ChatPaneComponent {
    override val state = MutableStateFlow(initialState)

    override fun onIntent(intent: ChatIntent) = Unit
}

/** A host with an open session, so the previews show the conversation rather than the agent picker. */
private val PreviewHost = HostChatState(sessionId = "preview")

/** Before a conversation: one installed agent with its history open, one to download. */
@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ChatAgentPickerPreview() {
    TennyoTheme {
        ChatPane(
            component = PreviewChatComponent(
                ChatState(
                    available = true,
                    loaded = true,
                    historyAgentId = "claude",
                    host = HostChatState(
                        agentId = "claude",
                        agents = listOf(
                            ChatAgent("claude", "Claude Code"),
                            ChatAgent("gemini", "Gemini CLI", installed = false),
                        ),
                        sessions = listOf(ChatSessionSummary("s1", "Fix the pairing timeout", "2026-09-25 18:04")),
                    ),
                ),
            ),
            panePadding = PreviewPanePadding,
        )
    }
}

private val PreviewPanePadding = PanePadding(
    contentPadding = PaddingValues(top = Spacing.large),
    bottomSafeInset = Spacing.none,
    contentHorizontalPadding = Spacing.large,
)
