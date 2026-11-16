package app.tennyo.mobile.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.tennyo.contract.ChatCommand
import app.tennyo.contract.ChatConfigOption
import app.tennyo.contract.ChatFailure
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.ChatMode
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoDropdownMenu
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.TennyoPromptTextField
import app.tennyo.mobile.design.TennyoSwitch
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.layout.PlatformTopAppBar
import app.tennyo.mobile.design.layout.PlatformTopAppBarSize
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_agent_working
import app.tennyo.mobile.feature.chat.generated.resources.chat_attach_image
import app.tennyo.mobile.feature.chat.generated.resources.chat_composer_placeholder
import app.tennyo.mobile.feature.chat.generated.resources.chat_empty_body
import app.tennyo.mobile.feature.chat.generated.resources.chat_empty_title
import app.tennyo.mobile.feature.chat.generated.resources.chat_image_attached
import app.tennyo.mobile.feature.chat.generated.resources.chat_restart
import app.tennyo.mobile.feature.chat.generated.resources.chat_send
import app.tennyo.mobile.feature.chat.generated.resources.chat_stop
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException

/**
 * The conversation as a screen of its own, over the workspace: its own top bar with the way back to the agent list,
 * the agent's settings, the transcript and the composer.
 *
 * The transcript runs the full height of the screen; the top bar and the composer float over it, and the list is
 * inset by their measured heights so its first and last lines still come to rest clear of them.
 */
@Composable
fun ChatConversationScreen(component: ChatPaneComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val onIntent = component::onIntent
    val agentName = state.agents.firstOrNull { it.id == state.agentId }?.name.orEmpty()
    val density = LocalDensity.current
    var topChrome by remember { mutableStateOf(0.dp) }
    var bottomChrome by remember { mutableStateOf(0.dp) }
    val background = MaterialTheme.colorScheme.background
    // A Surface rather than a background: it also gives the transcript its content colour for the theme.
    Surface(modifier = modifier.fillMaxSize(), color = background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.blocks.isEmpty() && !state.busy && state.failure == null) {
                EmptyConversation(
                    agentName = agentName,
                    modifier = Modifier.fillMaxSize().padding(top = topChrome, bottom = bottomChrome),
                )
            } else {
                Transcript(
                    state = state,
                    onIntent = onIntent,
                    contentPadding = PaddingValues(
                        start = Spacing.large,
                        top = topChrome + Spacing.medium,
                        end = Spacing.large,
                        bottom = bottomChrome + Spacing.medium,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PlatformTopAppBar(
                title = agentName,
                onBack = { onIntent(ChatIntent.ShowAgents) },
                actions = {},
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Solid under the status bar, clear by the bar's lower edge.
                    .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f))))
                    .onSizeChanged { topChrome = with(density) { it.height.toDp() } },
                size = PlatformTopAppBarSize.Compact,
                bottomContent = {
                    SessionSettings(
                        modes = state.host.modes,
                        currentModeId = state.host.currentModeId,
                        options = state.host.configOptions,
                        onIntent = onIntent,
                    )
                },
                containerColor = Color.Transparent,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background)))
                    .onSizeChanged { bottomChrome = with(density) { it.height.toDp() } }
                    // The safe bottom inset grows with the keyboard, which keeps the composer above it.
                    .padding(bottom = safeDrawingBottomInset()),
            ) {
                CommandSuggestions(
                    commands = commandSuggestions(state.composerText, state.host.commands),
                    onPicked = { onIntent(ChatIntent.CommandPicked(it)) },
                )
                PendingImages(count = state.pendingImages.size, onRemove = { onIntent(ChatIntent.ImageRemoved(it)) })
                ChatComposer(
                    text = state.composerText,
                    // A picked command replaces the draft the field is focused on, which only a new identity does.
                    identity = state.composerRevision,
                    // A permission or a form has to be resolved before the conversation moves on.
                    enabled = state.blockedOn == null && state.failure?.kind != ChatFailureKind.AGENT_EXITED,
                    busy = state.busy,
                    imagesAllowed = state.host.promptCapabilities.image,
                    onIntent = onIntent,
                    sendable = state.composerText.isNotBlank() || state.pendingImages.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun Transcript(
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // New output keeps the end in view until the user scrolls back to read; scrolling to the end again resumes it.
    var following by remember { mutableStateOf(true) }
    val readingBack = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && listState.canScrollBackward) following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        // Judged only once scrolling has settled: mid-gesture the layout still reports the position before the drag.
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .filter { (scrolling, _) -> !scrolling }
            .collect { (_, moreBelow) ->
                when {
                    // At the end, however it got there — scrolled back down, or the content shrank to fit.
                    !moreBelow -> following = true
                    // Whatever grew below the fold: a new block, a streaming reply, an unfolding row, the keyboard.
                    following -> runCatching {
                        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
                    }.onFailure { failure ->
                        // A touch outranks this scroll and cancels it; once the touch settles the flow looks again.
                        if (failure !is CancellationException) throw failure
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.nestedScroll(readingBack),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        itemsIndexed(state.blocks, key = { index, _ -> index }) { index, block ->
            ChatBlockItem(
                block = block,
                live = state.busy && index == state.blocks.lastIndex,
                onToggled = { onIntent(ChatIntent.BlockToggled(index)) },
                onPermissionDecided = { id, decision -> onIntent(ChatIntent.PermissionDecided(id, decision)) },
                formDraft = { id -> state.formDrafts[id].orEmpty() },
                onFormValueChanged = { id, field, value ->
                    onIntent(ChatIntent.FormValueChanged(id, field, value))
                },
                onFormAnswered = { id, action -> onIntent(ChatIntent.FormAnswered(id, action)) },
            )
        }
        if (state.busy && state.blockedOn == null) {
            item(key = "working") {
                TennyoWorkingIndicator(label = stringResource(Res.string.chat_agent_working))
            }
        }
        state.failure?.let { failure ->
            item(key = "failure") {
                FailureNotice(
                    failure = failure,
                    onRestart = state.agentId?.let { agentId -> { onIntent(ChatIntent.StartAgent(agentId)) } },
                )
            }
        }
    }
}

/** A fresh conversation: what the agent can reach and how to point it at things, sitting just above the composer. */
@Composable
private fun EmptyConversation(agentName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.Bottom),
    ) {
        Text(stringResource(Res.string.chat_empty_title, agentName), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(Res.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The agent's modes and settings, as dropdowns and switches in one scrollable row. Nothing when it has none. */
@Composable
private fun SessionSettings(
    modes: List<ChatMode>,
    currentModeId: String?,
    options: List<ChatConfigOption>,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (modes.isEmpty() && options.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.large, vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (modes.isNotEmpty()) {
            Picker(
                selected = modes.indexOfFirst { it.id == currentModeId },
                labels = modes.map { it.name },
                onSelected = { onIntent(ChatIntent.SetMode(modes[it].id)) },
            )
        }
        options.forEach { option ->
            key(option.id) {
                if (option.isSwitch) {
                    // Tighter than the gap between settings, so each switch stays with its own name.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.name, style = MaterialTheme.typography.labelLarge)
                        TennyoSwitch(
                            checked = option.value == "true",
                            onCheckedChange = { onIntent(ChatIntent.SetConfig(option.id, it.toString())) },
                        )
                    }
                } else {
                    Picker(
                        selected = option.choices.indexOfFirst { it.value == option.value },
                        labels = option.choices.map { it.name },
                        onSelected = { onIntent(ChatIntent.SetConfig(option.id, option.choices[it].value)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Picker(selected: Int, labels: List<String>, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    TennyoDropdownMenu(
        selectedLabel = labels.getOrNull(selected).orEmpty(),
        options = labels,
        selectedIndex = selected,
        onSelected = onSelected,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    )
}

/**
 * The commands the draft is typing, as a card over the transcript. It rises and sinks as a whole; while it is open,
 * narrowing the list only resizes it.
 */
@Composable
private fun CommandSuggestions(commands: List<ChatCommand>, onPicked: (String) -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = commands.take(MAX_SUGGESTIONS),
        modifier = modifier.fillMaxWidth(),
        transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = true) },
        contentAlignment = Alignment.BottomCenter,
        contentKey = { it.isEmpty() },
    ) { shown ->
        if (shown.isEmpty()) return@AnimatedContent
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
            shape = Corners.xl,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Borders.regular, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.animateContentSize().padding(Spacing.extraSmall)) {
                shown.forEach { command ->
                    key(command.name) {
                        TennyoListRow(
                            title = "/${command.name}",
                            subtitle = command.description,
                            onClick = { onPicked(command.name) },
                            leading = { Icon(TennyoIcons.Code, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImages(count: Int, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (count == 0) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        repeat(count) { index ->
            TennyoButton(
                label = stringResource(Res.string.chat_image_attached, index + 1),
                icon = TennyoIcons.Close,
                onClick = { onRemove(index) },
                style = TennyoButtonStyle.Secondary,
                size = TennyoButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ChatComposer(
    text: String,
    identity: Int,
    enabled: Boolean,
    busy: Boolean,
    imagesAllowed: Boolean,
    sendable: Boolean,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickImage = rememberImagePicker { mimeType, base64 -> onIntent(ChatIntent.ImagePicked(mimeType, base64)) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (imagesAllowed && pickImage != null) {
            TennyoIconButton(
                icon = TennyoIcons.Add,
                contentDescription = stringResource(Res.string.chat_attach_image),
                onClick = pickImage,
                enabled = enabled,
            )
        }
        TennyoPromptTextField(
            value = text,
            onValueChange = { onIntent(ChatIntent.ComposerChanged(it)) },
            placeholder = stringResource(Res.string.chat_composer_placeholder),
            identityKey = identity,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        // While the agent works the send button becomes stop: one turn at a time.
        if (busy) {
            TennyoIconButton(
                icon = TennyoIcons.Stop,
                contentDescription = stringResource(Res.string.chat_stop),
                onClick = { onIntent(ChatIntent.Stop) },
            )
        } else {
            // Filled once there is something to send, so the one action that moves the conversation stands out.
            val ready = enabled && sendable
            val colors = MaterialTheme.colorScheme
            TennyoIconButton(
                icon = TennyoIcons.Send,
                contentDescription = stringResource(Res.string.chat_send),
                onClick = { onIntent(ChatIntent.Send) },
                enabled = ready,
                contentColor = if (ready) colors.onPrimary else colors.onSurfaceVariant,
                containerColor = if (ready) colors.primary else Color.Transparent,
            )
        }
    }
}

@Composable
private fun FailureNotice(
    failure: ChatFailure,
    onRestart: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = stringResource(failure.kind.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onRestart?.let { restart ->
            TennyoButton(
                label = stringResource(Res.string.chat_restart),
                onClick = restart,
                style = TennyoButtonStyle.Secondary,
            )
        }
    }
}

private const val MAX_SUGGESTIONS = 5
