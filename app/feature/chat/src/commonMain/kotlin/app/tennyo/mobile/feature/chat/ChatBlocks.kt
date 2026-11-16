package app.tennyo.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tennyo.contract.ChatFormAction
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoCodeSurface
import app.tennyo.mobile.design.TennyoCodeText
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoOrbitIndicator
import app.tennyo.mobile.design.TennyoStatusChip
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoUnifiedDiffRow
import app.tennyo.mobile.design.TennyoUnifiedDiffSurface
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_diff_counts
import app.tennyo.mobile.feature.chat.generated.resources.chat_permission_allow
import app.tennyo.mobile.feature.chat.generated.resources.chat_permission_allowed
import app.tennyo.mobile.feature.chat.generated.resources.chat_permission_denied
import app.tennyo.mobile.feature.chat.generated.resources.chat_permission_deny
import app.tennyo.mobile.feature.chat.generated.resources.chat_scope_always
import app.tennyo.mobile.feature.chat.generated.resources.chat_scope_once
import app.tennyo.mobile.feature.chat.generated.resources.chat_thinking
import app.tennyo.mobile.feature.chat.generated.resources.chat_thought
import org.jetbrains.compose.resources.stringResource

/** The height every piece of machine work collapses to, so a long tool run never crowds the conversation. */
private val MachineWorkRowHeight = 36.dp

private const val MAX_OUTPUT_LINES = 400

private const val HALF_TURN = 180f

/** A live thought breathes between full and this opacity, so the row reads as still going. */
private const val THINKING_DIM = 0.35f
private const val THINKING_PULSE_MILLIS = 900

/**
 * One block, at the weight its type carries. Content sits flat on the background, machine work collapses into a
 * quiet row, and anything the agent is blocked on becomes a card. [live] marks the block the agent is still on.
 */
@Composable
internal fun ChatBlockItem(
    block: ChatBlock,
    live: Boolean,
    onToggled: () -> Unit,
    onPermissionDecided: (String, PermissionDecision) -> Unit,
    formDraft: (String) -> Map<String, String>,
    onFormValueChanged: (String, String, String) -> Unit,
    onFormAnswered: (String, ChatFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ChatBlock.Prompt -> PromptBlock(block, modifier)
        is ChatBlock.Prose -> ProseBlock(block, modifier)
        is ChatBlock.Diff -> DiffBlock(block, modifier)
        is ChatBlock.Thinking -> ThinkingBlock(block, live, onToggled, modifier)
        is ChatBlock.ToolStep -> ToolStepBlock(block, onToggled, modifier)
        is ChatBlock.Permission -> PermissionCard(block, onPermissionDecided, modifier)
        is ChatBlock.Plan -> PlanBlock(block, modifier)
        is ChatBlock.Form -> FormCard(block, formDraft(block.id), onFormValueChanged, onFormAnswered, modifier)
    }
}

@Composable
private fun PromptBlock(block: ChatBlock.Prompt, modifier: Modifier = Modifier) {
    // The leading inset keeps the user's side of the conversation recognisable however long the prompt runs.
    Row(
        modifier = modifier.fillMaxWidth().padding(start = Spacing.extraLarge * 2),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(shape = Corners.xl, color = MaterialTheme.colorScheme.secondaryContainer) {
            SelectionContainer {
                Text(
                    // Attachments ride under the text, one per line, as the agent received them.
                    text = (listOf(block.text).filter(String::isNotEmpty) + block.attachments.map { "@$it" })
                        .joinToString("\n"),
                    modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.medium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ProseBlock(block: ChatBlock.Prose, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) { MarkdownContent(block.markdown) }
}

@Composable
private fun DiffBlock(block: ChatBlock.Diff, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TennyoIcons.Edit,
                contentDescription = null,
                modifier = Modifier.size(ComponentSizes.compactIcon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = block.fileName,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TennyoStatusChip(
                label = stringResource(Res.string.chat_diff_counts, block.addedLines, block.removedLines),
                tone = TennyoStatusTone.Connected,
                showDot = false,
            )
        }
        TennyoUnifiedDiffSurface(
            rows = block.hunk.mapIndexed { index, line -> line.toDiffRow(index) },
            modifier = Modifier.fillMaxWidth().heightIn(max = ComponentSizes.outputMaxHeight),
            scrollIdentity = block.fileName,
        )
    }
}

private fun DiffLine.toDiffRow(index: Int): TennyoUnifiedDiffRow =
    TennyoUnifiedDiffRow(
        oldLineNumber = if (kind == DiffLineKind.ADDED) null else index + 1,
        newLineNumber = if (kind == DiffLineKind.REMOVED) null else index + 1,
        marker = when (kind) {
            DiffLineKind.ADDED -> "+"
            DiffLineKind.REMOVED -> "-"
            DiffLineKind.CONTEXT -> " "
        },
        text = text,
        added = kind == DiffLineKind.ADDED,
        removed = kind == DiffLineKind.REMOVED,
    )

/** Machine work: a quiet row, breathing while the agent still thinks, which opens only if the user wants it. */
@Composable
private fun ThinkingBlock(
    block: ChatBlock.Thinking,
    live: Boolean,
    onToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MachineWorkRow(
            label = stringResource(if (live) Res.string.chat_thinking else Res.string.chat_thought),
            onClick = onToggled,
            leading = { ThinkingIcon(live) },
        ) {
            val turn by animateFloatAsState(if (block.expanded) HALF_TURN else 0f)
            MachineWorkIcon(TennyoIcons.KeyboardArrowDown, Modifier.rotate(turn))
        }
        Unfolding(block.expanded) {
            Text(
                text = block.text,
                modifier = Modifier.padding(start = Spacing.extraLarge, top = Spacing.extraSmall),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingIcon(live: Boolean, modifier: Modifier = Modifier) {
    if (!live) {
        MachineWorkIcon(TennyoIcons.Lightbulb, modifier)
        return
    }
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = THINKING_DIM,
        animationSpec = infiniteRepeatable(tween(THINKING_PULSE_MILLIS), RepeatMode.Reverse),
    )
    MachineWorkIcon(TennyoIcons.Lightbulb, modifier.alpha(pulse), tint = MaterialTheme.colorScheme.primary)
}

/** What a machine-work row opens to, unfolding beneath it instead of appearing at once. */
@Composable
private fun Unfolding(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        content()
    }
}

/** Machine work: one step and how it ended; a step that reported output opens to show it. */
@Composable
private fun ToolStepBlock(block: ChatBlock.ToolStep, onToggled: () -> Unit, modifier: Modifier = Modifier) {
    val output = block.detail?.takeIf(String::isNotBlank)
    Column(modifier = modifier.fillMaxWidth()) {
        ToolStepRow(block, onClick = output?.let { onToggled })
        Unfolding(block.expanded && output != null) {
            TennyoCodeSurface(modifier = Modifier.fillMaxWidth().padding(top = Spacing.extraSmall)) {
                // Only the last MAX_OUTPUT_LINES lines, at full height inside the chat list's own scroll.
                Column {
                    output.orEmpty().lines().takeLast(MAX_OUTPUT_LINES).forEach { line ->
                        TennyoCodeText(text = line, modifier = Modifier.fillMaxWidth(), softWrap = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStepRow(block: ChatBlock.ToolStep, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val icon = when (block.kind) {
        ToolStepKind.READ -> TennyoIcons.Description
        ToolStepKind.EDIT -> TennyoIcons.Edit
        ToolStepKind.COMMAND -> TennyoIcons.Terminal
        ToolStepKind.SEARCH -> TennyoIcons.Search
    }
    MachineWorkRow(
        label = block.label,
        onClick = onClick,
        leading = { MachineWorkIcon(icon) },
        modifier = modifier,
    ) {
        when (block.status) {
            ToolStepStatus.RUNNING -> TennyoOrbitIndicator(indicatorSize = ComponentSizes.compactIcon)
            ToolStepStatus.DONE -> MachineWorkIcon(TennyoIcons.Check)
            // A failed step is the one row in a long run the user has to find at a glance.
            ToolStepStatus.FAILED -> MachineWorkIcon(TennyoIcons.ErrorOutline, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MachineWorkRow(
    label: String,
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MachineWorkRowHeight)
            .clip(Corners.sm)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing()
    }
}

@Composable
private fun MachineWorkIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(ComponentSizes.compactIcon),
        tint = tint,
    )
}

/** Blocking: the conversation does not move on until the user allows or denies it. */
@Composable
private fun PermissionCard(
    block: ChatBlock.Permission,
    onDecided: (String, PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockingCard(icon = TennyoIcons.Terminal, heading = block.action, modifier = modifier) {
        Text(
            text = stringResource(
                when (block.scope) {
                    PermissionScope.ONCE -> Res.string.chat_scope_once
                    PermissionScope.ALWAYS -> Res.string.chat_scope_always
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (block.decision) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoButton(
                    label = stringResource(Res.string.chat_permission_deny),
                    onClick = { onDecided(block.id, PermissionDecision.DENIED) },
                    modifier = Modifier.weight(1f),
                    style = TennyoButtonStyle.Secondary,
                    size = TennyoButtonSize.Small,
                )
                TennyoButton(
                    label = stringResource(Res.string.chat_permission_allow),
                    onClick = { onDecided(block.id, PermissionDecision.ALLOWED) },
                    modifier = Modifier.weight(1f),
                    style = TennyoButtonStyle.Primary,
                    size = TennyoButtonSize.Small,
                )
            }

            PermissionDecision.ALLOWED -> TennyoStatusChip(
                label = stringResource(Res.string.chat_permission_allowed),
                tone = TennyoStatusTone.Connected,
            )

            PermissionDecision.DENIED -> TennyoStatusChip(
                label = stringResource(Res.string.chat_permission_denied),
                tone = TennyoStatusTone.Neutral,
            )
        }
    }
}

@Composable
internal fun BlockingCard(
    icon: ImageVector,
    heading: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.xl,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // What the agent waits on is the heading, so the reason the conversation stopped reads first.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(top = ComponentSizes.iconTopAlignmentPadding).size(ComponentSizes.icon),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = heading,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            content()
        }
    }
}
