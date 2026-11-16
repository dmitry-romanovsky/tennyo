package app.tennyo.mobile.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoKeyCap
import app.tennyo.mobile.design.TennyoSwitch
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.settings_built_in_keys
import app.tennyo.mobile.feature.terminal.generated.resources.settings_custom_hotkeys
import app.tennyo.mobile.feature.terminal.generated.resources.settings_hotkeys_hint
import app.tennyo.mobile.feature.terminal.generated.resources.settings_no_custom_hotkeys
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_add
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_limit_reached
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_remove
import org.jetbrains.compose.resources.stringResource

@Composable
fun TerminalHotkeysPage(
    keyBar: TerminalKeyBarSettings,
    onEdit: (KeyBarEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addHotkeySheetVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        HotkeysSectionTitle(stringResource(Res.string.settings_built_in_keys))
        HotkeysCard {
            ToggleableTerminalKey.entries.forEachIndexed { index, builtIn ->
                key(builtIn) {
                    if (index > 0) HotkeysRowDivider()
                    HotkeyRow(
                        keyCap = builtIn.namedKey.shortLabel,
                        name = builtIn.namedKey.spokenLabel(),
                        enabled = builtIn !in keyBar.hiddenBuiltInKeys,
                        onEnabledChange = { visible ->
                            onEdit(KeyBarEdit.SetBuiltInKeyVisible(builtIn, visible))
                        },
                    ) {
                        // Keeps built-in switches aligned with the custom rows' switches.
                        Spacer(modifier = Modifier.size(ComponentSizes.iconButton))
                    }
                }
            }
        }
        HotkeysSectionTitle(
            text = stringResource(Res.string.settings_custom_hotkeys),
            modifier = Modifier.padding(top = Spacing.extraSmall),
        )
        HotkeysCard {
            if (keyBar.customHotkeys.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_no_custom_hotkeys),
                    modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.medium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            keyBar.customHotkeys.forEachIndexed { index, hotkey ->
                key(hotkey.chord.id) {
                    if (index > 0) HotkeysRowDivider()
                    CustomHotkeyRow(hotkey = hotkey, onEdit = onEdit)
                }
            }
        }
        TennyoButton(
            label = stringResource(Res.string.terminal_hotkey_add),
            icon = TennyoIcons.Add,
            onClick = { addHotkeySheetVisible = true },
            enabled = !keyBar.isAtHotkeyLimit,
            style = TennyoButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (keyBar.isAtHotkeyLimit) {
            HotkeysHint(stringResource(Res.string.terminal_hotkey_limit_reached))
        }
        HotkeysHint(stringResource(Res.string.settings_hotkeys_hint))
    }
    if (addHotkeySheetVisible) {
        AddTerminalHotkeySheet(
            keyBar = keyBar,
            onAdd = { hotkey -> onEdit(KeyBarEdit.AddHotkey(hotkey)) },
            onDismiss = { addHotkeySheetVisible = false },
        )
    }
}

@Composable
private fun CustomHotkeyRow(
    hotkey: CustomTerminalHotkey,
    onEdit: (KeyBarEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = hotkey.name.ifBlank { hotkey.chord.accessibilityLabel() }
    HotkeyRow(
        keyCap = hotkey.chord.label(),
        name = displayName,
        enabled = hotkey.enabled,
        onEnabledChange = { enabled -> onEdit(KeyBarEdit.SetHotkeyEnabled(hotkey.chord.id, enabled)) },
        modifier = modifier,
    ) {
        TennyoIconButton(
            icon = TennyoIcons.Close,
            contentDescription = stringResource(Res.string.terminal_hotkey_remove, displayName),
            onClick = { onEdit(KeyBarEdit.RemoveHotkey(hotkey.chord.id)) },
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HotkeyRow(
    keyCap: String,
    name: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.large, end = Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoKeyCap(label = keyCap)
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TennyoSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        trailing()
    }
}

@Composable
private fun HotkeysCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.lg)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(Borders.regular, MaterialTheme.colorScheme.outlineVariant, Corners.lg),
        content = content,
    )
}

@Composable
private fun HotkeysRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = Spacing.large),
        thickness = Borders.regular,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun HotkeysSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun HotkeysHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(widthDp = 400, heightDp = 640)
@Composable
private fun SettingsHotkeysPagePopulatedPreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark) {
        SettingsHotkeysPagePreviewContent(previewKeyBar())
    }
}

@Preview(widthDp = 400, heightDp = 480)
@Composable
private fun SettingsHotkeysPageEmptyPreview() {
    TennyoTheme(themeMode = AppThemeMode.Light) {
        SettingsHotkeysPagePreviewContent(TerminalKeyBarSettings())
    }
}

@Composable
private fun SettingsHotkeysPagePreviewContent(
    keyBar: TerminalKeyBarSettings,
) {
    TerminalHotkeysPage(
        keyBar = keyBar,
        onEdit = {},
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.extraLarge),
    )
}

/** A key bar with custom hotkeys, one of them hidden. */
private fun previewKeyBar() = TerminalKeyBarSettings(
    hiddenBuiltInKeys = setOf(ToggleableTerminalKey.PageDown),
    customHotkeys = listOf(
        CustomTerminalHotkey(TerminalKeyChord('d', TerminalModifiers(ctrl = true)), "EOF"),
        CustomTerminalHotkey(TerminalKeyChord('b', TerminalModifiers(alt = true)), "Word back"),
        CustomTerminalHotkey(TerminalKeyChord(NamedTerminalKey.F5), "", enabled = false),
    ),
)
