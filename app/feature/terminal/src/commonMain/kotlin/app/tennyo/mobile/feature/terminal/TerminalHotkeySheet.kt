package app.tennyo.mobile.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoAccessoryKey
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoDropdownMenu
import app.tennyo.mobile.design.TennyoFocusedTextField
import app.tennyo.mobile.design.TennyoKeyCap
import app.tennyo.mobile.design.TennyoSegmentedTabs
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_add
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_already_on_key_bar
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_character_kind
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_choose_key
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_duplicate
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_invalid_character
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_key
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_limit_reached
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_modifiers
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_name
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_pick_key_to_finish
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_preview
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_special_key_kind
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_unsupported_ctrl_character
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource

/**
 * Adds one custom hotkey. Used over the terminal (so the project screen keeps its lifecycle) and from Settings.
 * The draft is form input that dies with the sheet, so it is saved UI state rather than component state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTerminalHotkeySheet(
    keyBar: TerminalKeyBarSettings,
    onAdd: (CustomTerminalHotkey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(stateSaver = TerminalHotkeyDraftSaver) { mutableStateOf(TerminalHotkeyDraft()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        TerminalHotkeySheetContent(
            draft = draft,
            result = keyBar.validate(draft),
            onDraftChange = { draft = it },
            onAdd = { hotkey ->
                onAdd(hotkey)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TerminalHotkeySheetContent(
    draft: TerminalHotkeyDraft,
    result: TerminalHotkeyDraftResult,
    onDraftChange: (TerminalHotkeyDraft) -> Unit,
    onAdd: (CustomTerminalHotkey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(
            text = stringResource(Res.string.terminal_hotkey_add),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        TennyoFocusedTextField(
            label = stringResource(Res.string.terminal_hotkey_name),
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it.take(MAX_TERMINAL_HOTKEY_NAME_LENGTH))) },
            modifier = Modifier.fillMaxWidth(),
        )
        HotkeyModifierToggles(
            modifiers = draft.modifiers,
            onModifiersChange = { onDraftChange(draft.copy(modifiers = it)) },
        )
        TennyoSegmentedTabs(
            items = listOf(
                stringResource(Res.string.terminal_hotkey_character_kind),
                stringResource(Res.string.terminal_hotkey_special_key_kind),
            ),
            selectedIndex = draft.keyKind.ordinal,
            onSelected = { index -> onDraftChange(draft.copy(keyKind = TerminalHotkeyKeyKind.entries[index])) },
            modifier = Modifier.fillMaxWidth(),
        )
        when (draft.keyKind) {
            TerminalHotkeyKeyKind.Character -> HotkeyKeyField(
                key = draft.character,
                onKeyChange = { onDraftChange(draft.withCharacterInput(it)) },
                label = stringResource(Res.string.terminal_hotkey_key),
            )

            TerminalHotkeyKeyKind.Named -> NamedKeyDropdown(
                selected = draft.namedKey,
                onSelected = { onDraftChange(draft.copy(namedKey = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Screens 18 and 18b: the chord as it will read on that machine, or what is still missing.
        HotkeyPreviewRow(
            chordLabel = draft.normalizedChord()?.label(),
            name = draft.name,
        )
        (result as? TerminalHotkeyDraftResult.Invalid)?.error?.message()?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TennyoButton(
            label = stringResource(Res.string.terminal_hotkey_add),
            onClick = { (result as? TerminalHotkeyDraftResult.Valid)?.let { onAdd(it.hotkey) } },
            enabled = result is TerminalHotkeyDraftResult.Valid,
            size = TennyoButtonSize.PrimaryAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HotkeyModifierToggles(
    modifiers: TerminalModifiers,
    onModifiersChange: (TerminalModifiers) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.terminal_hotkey_modifiers),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalModifierKey.entries.forEach { modifierKey ->
            key(modifierKey) {
                TennyoAccessoryKey(
                    label = modifierKey.label,
                    contentDescription = stringResource(modifierKey.spokenName),
                    onClick = { onModifiersChange(modifiers.toggled(modifierKey)) },
                    selected = modifierKey in modifiers,
                    toggle = true,
                    modifier = Modifier
                        .widthIn(min = ComponentSizes.accessoryKeyMinWidth)
                        .height(ComponentSizes.accessoryKeyHeight),
                )
            }
        }
    }
}

@Composable
private fun NamedKeyDropdown(
    selected: NamedTerminalKey?,
    onSelected: (NamedTerminalKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    TennyoDropdownMenu(
        selectedLabel = selected?.spokenLabel() ?: stringResource(Res.string.terminal_hotkey_choose_key),
        options = NamedTerminalKey.entries.map { it.spokenLabel() },
        selectedIndex = selected?.ordinal ?: -1,
        onSelected = { index -> onSelected(NamedTerminalKey.entries[index]) },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        fillAnchor = true,
        modifier = modifier,
    )
}

@Composable
private fun TerminalHotkeyDraftError.message(): String? =
    when (this) {
        // A missing key only disables Add; the empty form needs no error.
        TerminalHotkeyDraftError.MissingKey -> null
        TerminalHotkeyDraftError.InvalidCharacter -> stringResource(Res.string.terminal_hotkey_invalid_character)
        TerminalHotkeyDraftError.UnsupportedCtrlCharacter ->
            stringResource(Res.string.terminal_hotkey_unsupported_ctrl_character)
        TerminalHotkeyDraftError.AlreadyOnKeyBar -> stringResource(Res.string.terminal_hotkey_already_on_key_bar)
        TerminalHotkeyDraftError.Duplicate -> stringResource(Res.string.terminal_hotkey_duplicate)
        TerminalHotkeyDraftError.LimitReached ->
            stringResource(Res.string.terminal_hotkey_limit_reached, MAX_CUSTOM_TERMINAL_HOTKEYS)
    }

private val draftJson = Json { ignoreUnknownKeys = true }

private val TerminalHotkeyDraftSaver = Saver<TerminalHotkeyDraft, String>(
    save = { draftJson.encodeToString(it) },
    restore = { runCatching { draftJson.decodeFromString<TerminalHotkeyDraft>(it) }.getOrNull() },
)

@Preview(widthDp = 400, heightDp = 480)
@Composable
private fun TerminalHotkeySheetEmptyPreview() {
    TerminalHotkeySheetPreview(TerminalKeyBarSettings(), TerminalHotkeyDraft())
}

@Preview(widthDp = 400, heightDp = 520)
@Composable
private fun TerminalHotkeySheetValidPreview() {
    TerminalHotkeySheetPreview(
        keyBar = TerminalKeyBarSettings(),
        draft = TerminalHotkeyDraft(name = "EOF", modifiers = TerminalModifiers(ctrl = true), character = "d"),
        themeMode = AppThemeMode.Dark,
    )
}

@Preview(widthDp = 400, heightDp = 540)
@Composable
private fun TerminalHotkeySheetAlreadyOnKeyBarPreview() {
    TerminalHotkeySheetPreview(
        keyBar = TerminalKeyBarSettings(),
        draft = TerminalHotkeyDraft(modifiers = TerminalModifiers(ctrl = true), character = "c"),
    )
}

@Preview(widthDp = 400, heightDp = 520)
@Composable
private fun TerminalHotkeySheetLimitReachedPreview() {
    TerminalHotkeySheetPreview(
        keyBar = TerminalKeyBarSettings(
            customHotkeys = List(MAX_CUSTOM_TERMINAL_HOTKEYS) { index ->
                CustomTerminalHotkey(TerminalKeyChord('a' + index, TerminalModifiers(alt = true)), "")
            },
        ),
        draft = TerminalHotkeyDraft(keyKind = TerminalHotkeyKeyKind.Named, namedKey = NamedTerminalKey.F5),
    )
}

@Composable
private fun TerminalHotkeySheetPreview(
    keyBar: TerminalKeyBarSettings,
    draft: TerminalHotkeyDraft,
    themeMode: AppThemeMode = AppThemeMode.Light,
) {
    TennyoTheme(themeMode = themeMode) {
        TerminalHotkeySheetContent(
            draft = draft,
            result = keyBar.validate(draft),
            onDraftChange = {},
            onAdd = {},
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = Spacing.large),
        )
    }
}

/**
 * The preview row screens 18 and 18b end on: the chord as this layout writes it beside the name it will carry,
 * or — with no key picked yet — what is still needed before the hotkey can be added.
 */
@Composable
private fun HotkeyPreviewRow(
    chordLabel: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoKeyCap(label = chordLabel ?: INCOMPLETE_CHORD_LABEL)
        Text(
            text = name.ifBlank { stringResource(Res.string.terminal_hotkey_pick_key_to_finish) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (chordLabel == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = stringResource(Res.string.terminal_hotkey_preview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The key of a character hotkey: one key, shown as a large character in the primary colour so it reads like the key
 * cap it becomes. Typing replaces the key rather than appending to it.
 */
@Composable
private fun HotkeyKeyField(
    key: String,
    onKeyChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = key,
            // The newest character wins, so a second key press swaps the key instead of being refused.
            onValueChange = { onKeyChange(it.takeLast(1)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall.copy(
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            modifier = Modifier
                .size(HotkeyKeyFieldSize)
                .clip(Corners.md)
                .background(colors.primaryContainer.copy(alpha = HOTKEY_KEY_FIELD_ALPHA))
                .border(Borders.regular, colors.primary, Corners.md)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** What the key cap shows before a key is picked. */
private const val INCOMPLETE_CHORD_LABEL = "Ctrl+…"

private val HotkeyKeyFieldSize = 72.dp
private const val HOTKEY_KEY_FIELD_ALPHA = 0.35f
