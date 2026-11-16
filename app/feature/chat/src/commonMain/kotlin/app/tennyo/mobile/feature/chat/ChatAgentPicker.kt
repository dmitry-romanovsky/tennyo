package app.tennyo.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatSessionSummary
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.TennyoProjectAvatar
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.relativeTime
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_active_conversation
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_available
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_downloads
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_earlier
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_installed
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_new_conversation
import app.tennyo.mobile.feature.chat.generated.resources.chat_agents_loading
import app.tennyo.mobile.feature.chat.generated.resources.chat_agents_none
import app.tennyo.mobile.feature.chat.generated.resources.chat_hide_active_chat
import app.tennyo.mobile.feature.chat.generated.resources.chat_history_empty
import app.tennyo.mobile.feature.chat.generated.resources.chat_history_loading
import app.tennyo.mobile.feature.chat.generated.resources.chat_open_conversation
import app.tennyo.mobile.feature.chat.generated.resources.chat_open_conversation_busy
import app.tennyo.mobile.feature.chat.generated.resources.chat_pick_agent_body
import app.tennyo.mobile.feature.chat.generated.resources.chat_pick_agent_title
import app.tennyo.mobile.feature.chat.generated.resources.chat_recent
import app.tennyo.mobile.feature.chat.generated.resources.chat_recent_subtitle
import app.tennyo.mobile.feature.chat.generated.resources.chat_untitled_session
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Before a conversation: which agent to talk to. A conversation the host still has open comes first, to return to as
 * it was; recent conversations across agents follow, to pick one up. Then the agents: a tap starts a new
 * conversation, the chevron lists that agent's earlier ones underneath it. Agents already on the computer come first;
 * the rest download on their first start.
 */
@Composable
internal fun AgentPicker(
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val (installed, available) = state.agents.partition(ChatAgent::installed)
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        item(key = "intro") { Intro() }
        when {
            !state.loaded -> item(key = "loading") {
                TennyoWorkingIndicator(label = stringResource(Res.string.chat_agents_loading))
            }

            state.agents.isEmpty() -> item(key = "none") { Hint(stringResource(Res.string.chat_agents_none)) }

            else -> {
                if (state.hasOpenConversation) item(key = "open") {
                    OpenConversationRow(
                        state,
                        onIntent
                    )
                }
                recentConversations(state, onIntent)
                agentGroup(Res.string.chat_agent_installed, installed, state, onIntent)
                agentGroup(Res.string.chat_agent_available, available, state, onIntent)
            }
        }
    }
}

private fun LazyListScope.agentGroup(
    title: StringResource,
    agents: List<ChatAgent>,
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
) {
    if (agents.isEmpty()) return
    item(key = "group:${title.key}") { GroupTitle(title) }
    agents.forEach { agent ->
        item(key = "agent:${agent.id}") { AgentRow(agent, state, onIntent) }
        item(key = "history:${agent.id}") {
            AnimatedVisibility(
                visible = state.historyAgentId == agent.id,
                enter = expandVertically(tween(AnimationDurations.quickMillis)) + fadeIn(),
                exit = shrinkVertically(tween(AnimationDurations.quickMillis)) + fadeOut(),
            ) {
                AgentHistory(agent, state, onIntent)
            }
        }
    }
}

private fun LazyListScope.recentConversations(state: ChatState, onIntent: (ChatIntent) -> Unit) {
    val recent = state.recent.filterNot { entry ->
        state.hasOpenConversation && entry.agentId == state.host.agentId && entry.sessionId == state.host.sessionId
    }
    if (recent.isEmpty()) return
    item(key = "group:recent") { GroupTitle(Res.string.chat_recent) }
    items(recent, key = { "recent:${it.agentId}:${it.sessionId}" }) { entry ->
        RecentRow(entry, state, onIntent)
    }
}

/** One earlier conversation: its title, the agent it ran on and when; a tap resumes it. */
@Composable
private fun RecentRow(
    entry: ChatHistoryEntry,
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agent = state.agents.firstOrNull { it.id == entry.agentId }?.name ?: entry.agentId
    val resuming = state.pending == ChatPending(entry.agentId, ChatPendingKind.START)
    TennyoListRow(
        title = entry.title ?: stringResource(Res.string.chat_untitled_session),
        subtitle = entry.updatedAt?.let {
            stringResource(
                Res.string.chat_recent_subtitle,
                agent,
                updatedLabel(it)
            )
        }
            ?: agent,
        onClick = { onIntent(ChatIntent.StartAgent(entry.agentId, entry.sessionId)) },
        enabled = state.pending == null,
        modifier = modifier.fillMaxWidth(),
        leading = {
            if (resuming) {
                TennyoProgressIndicator(indicatorSize = ComponentSizes.icon)
            } else {
                TennyoProjectAvatar(text = agent)
            }
        },
    )
}

/** The conversation the host still has open: back into it as it was, without starting the agent anew. */
@Composable
private fun OpenConversationRow(state: ChatState, onIntent: (ChatIntent) -> Unit, modifier: Modifier = Modifier) {
    val agent = state.agents.firstOrNull { it.id == state.agentId }?.name ?: state.agentId.orEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        GroupTitle(Res.string.chat_open_conversation)
        TennyoListRow(
            title = agent,
            subtitle = stringResource(
                if (state.busy) Res.string.chat_open_conversation_busy else Res.string.chat_active_conversation,
            ),
            onClick = { onIntent(ChatIntent.ReturnToConversation) },
            selected = true,
            modifier = Modifier.fillMaxWidth(),
            leading = {
                if (state.busy) {
                    TennyoProgressIndicator(indicatorSize = ComponentSizes.icon)
                } else {
                    TennyoProjectAvatar(text = agent, selected = true)
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TennyoIconButton(
                        icon = TennyoIcons.Close,
                        contentDescription = stringResource(Res.string.chat_hide_active_chat),
                        onClick = { onIntent(ChatIntent.HideActiveChat) },
                        size = ComponentSizes.iconButton,
                    )
                    Icon(
                        imageVector = TennyoIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun AgentRow(
    agent: ChatAgent,
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val starting = state.pending == ChatPending(agent.id, ChatPendingKind.START)
    TennyoListRow(
        title = agent.name,
        subtitle = stringResource(
            if (agent.installed) Res.string.chat_agent_new_conversation else Res.string.chat_agent_downloads,
        ),
        onClick = { onIntent(ChatIntent.StartAgent(agent.id)) },
        enabled = state.pending == null,
        modifier = modifier.fillMaxWidth(),
        leading = {
            if (starting) {
                TennyoProgressIndicator(indicatorSize = ComponentSizes.icon)
            } else {
                TennyoProjectAvatar(text = agent.name)
            }
        },
        trailing = {
            val open = state.historyAgentId == agent.id
            val rotation by animateFloatAsState(
                targetValue = if (open) HALF_TURN else 0f,
                animationSpec = tween(AnimationDurations.quickMillis),
                label = "Agent history chevron",
            )
            TennyoIconButton(
                icon = TennyoIcons.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.chat_agent_earlier, agent.name),
                onClick = { onIntent(ChatIntent.ToggleHistory(agent.id)) },
                modifier = Modifier.rotate(rotation),
                enabled = state.pending == null,
                contentColor = if (open) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

/** The agent's earlier conversations, indented under it; a tap resumes one. */
@Composable
private fun AgentHistory(
    agent: ChatAgent,
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = state.pending == ChatPending(agent.id, ChatPendingKind.HISTORY)
    val sessions: List<ChatSessionSummary> = state.host.sessions
        .takeIf { state.agentId == agent.id }
        .orEmpty()
        .filterNot { session -> state.hasOpenConversation && session.sessionId == state.host.sessionId }
    Column(
        modifier = modifier.fillMaxWidth().padding(start = Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        when {
            loading -> TennyoWorkingIndicator(
                label = stringResource(Res.string.chat_history_loading),
                modifier = Modifier.padding(Spacing.small),
            )

            sessions.isEmpty() -> Hint(stringResource(Res.string.chat_history_empty))
            else -> sessions.forEach { session ->
                TennyoListRow(
                    title = session.title ?: stringResource(Res.string.chat_untitled_session),
                    subtitle = session.updatedAt?.let { updatedLabel(it) },
                    onClick = { onIntent(ChatIntent.StartAgent(agent.id, session.sessionId)) },
                    enabled = state.pending == null,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        Icon(
                            imageVector = TennyoIcons.Replay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.extraSmall),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Intro(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            stringResource(Res.string.chat_pick_agent_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(Res.string.chat_pick_agent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupTitle(title: StringResource, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.medium, bottom = Spacing.extraSmall),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(Spacing.small),
    )
}

/** The host's ISO timestamp as "5m ago"; anything it cannot read is shown as sent. */
@Composable
private fun updatedLabel(updatedAt: String): String =
    runCatching { Instant.parse(updatedAt).toEpochMilliseconds() }.getOrNull()?.relativeTime()
        ?: updatedAt

private const val HALF_TURN = 180f
