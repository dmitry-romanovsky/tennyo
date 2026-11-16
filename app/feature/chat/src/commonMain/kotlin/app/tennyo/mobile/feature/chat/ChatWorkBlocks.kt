package app.tennyo.mobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFieldKind
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatPlanStatus
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoDropdownMenu
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusChip
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoSwitch
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_form_decline
import app.tennyo.mobile.feature.chat.generated.resources.chat_form_declined
import app.tennyo.mobile.feature.chat.generated.resources.chat_form_multi_hint
import app.tennyo.mobile.feature.chat.generated.resources.chat_form_sent
import app.tennyo.mobile.feature.chat.generated.resources.chat_form_submit
import app.tennyo.mobile.feature.chat.generated.resources.chat_plan
import org.jetbrains.compose.resources.stringResource

/** Machine work: the agent's plan, one line per step with how far it got. */
@Composable
internal fun PlanBlock(block: ChatBlock.Plan, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = stringResource(Res.string.chat_plan),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        block.steps.forEach { step ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (step.status) {
                        ChatPlanStatus.COMPLETED -> TennyoIcons.Check
                        ChatPlanStatus.IN_PROGRESS -> TennyoIcons.PlayArrow
                        ChatPlanStatus.PENDING -> TennyoIcons.MoreHoriz
                    },
                    contentDescription = null,
                    modifier = Modifier.size(ComponentSizes.compactIcon),
                    tint = if (step.status == ChatPlanStatus.IN_PROGRESS) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = step.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == ChatPlanStatus.COMPLETED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Blocking: the fields the agent asks for, typed values kept in the pane's state until sent. */
@Composable
internal fun FormCard(
    block: ChatBlock.Form,
    draft: Map<String, String>,
    onValueChanged: (String, String, String) -> Unit,
    onAnswered: (String, ChatFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockingCard(icon = TennyoIcons.Help, heading = block.message, modifier = modifier) {
        val answered = block.answer != null
        block.fields.forEach { field ->
            key(field.name) {
                FormField(
                    field = field,
                    value = draft[field.name] ?: field.default.orEmpty(),
                    enabled = !answered,
                    onValueChange = { onValueChanged(block.id, field.name, it) },
                )
            }
        }
        when (block.answer) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoButton(
                    label = stringResource(Res.string.chat_form_decline),
                    onClick = { onAnswered(block.id, ChatFormAction.DECLINE) },
                    modifier = Modifier.weight(1f),
                    style = TennyoButtonStyle.Secondary,
                    size = TennyoButtonSize.Small,
                )
                TennyoButton(
                    label = stringResource(Res.string.chat_form_submit),
                    onClick = { onAnswered(block.id, ChatFormAction.ACCEPT) },
                    enabled = block.fields.requiredFilled(draft),
                    modifier = Modifier.weight(1f),
                    size = TennyoButtonSize.Small,
                )
            }

            ChatFormAction.ACCEPT ->
                TennyoStatusChip(label = stringResource(Res.string.chat_form_sent), tone = TennyoStatusTone.Connected)
            else ->
                TennyoStatusChip(label = stringResource(Res.string.chat_form_declined), tone = TennyoStatusTone.Neutral)
        }
    }
}

/** Whether every required field has a value, typed or defaulted. */
private fun List<ChatField>.requiredFilled(draft: Map<String, String>): Boolean =
    filter { it.required }.all { !(draft[it.name] ?: it.default).isNullOrBlank() }

@Composable
private fun FormField(
    field: ChatField,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (field.required) "${field.title} *" else field.title
    when (field.kind) {
        ChatFieldKind.SWITCH -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TennyoSwitch(
                checked = value == "true",
                onCheckedChange = { onValueChange(it.toString()) },
                enabled = enabled,
            )
        }

        ChatFieldKind.CHOICE -> {
            var expanded by remember { mutableStateOf(false) }
            TennyoDropdownMenu(
                selectedLabel = field.choices.firstOrNull { it.value == value }?.name ?: label,
                options = field.choices.map { it.name },
                selectedIndex = field.choices.indexOfFirst { it.value == value },
                onSelected = { onValueChange(field.choices[it].value) },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                enabled = enabled,
                fillAnchor = true,
                modifier = modifier.fillMaxWidth(),
            )
        }

        else -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = { Text(label) },
            supportingText = field.description?.let { { Text(it) } }
                ?: if (field.kind == ChatFieldKind.MULTI_CHOICE) {
                    { Text(stringResource(Res.string.chat_form_multi_hint, field.choices.joinToString { it.value })) }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.kind == ChatFieldKind.NUMBER || field.kind == ChatFieldKind.INTEGER) {
                    KeyboardType.Number
                } else {
                    KeyboardType.Text
                },
            ),
            singleLine = true,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
