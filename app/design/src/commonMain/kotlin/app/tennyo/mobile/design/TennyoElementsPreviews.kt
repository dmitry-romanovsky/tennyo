// Sample data for the element catalog: the line numbers, sizes and revisions below are the picture itself.
@file:Suppress("MagicNumber")

package app.tennyo.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme

@Preview(widthDp = 420, heightDp = 2_120)
@Composable
private fun TennyoElementsCommonLightPreview() {
    TennyoElementsPreviewRoot(themeMode = AppThemeMode.Light)
}

@Preview(widthDp = 420, heightDp = 2_120)
@Composable
private fun TennyoElementsCommonDarkPreview() {
    TennyoElementsPreviewRoot(themeMode = AppThemeMode.Dark)
}

@Composable
private fun TennyoElementsPreviewRoot(themeMode: AppThemeMode) {
    TennyoTheme(
        themeMode = themeMode,
        colorScheme = AppColorScheme.TennyoTheme,
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        var switchChecked by remember { mutableStateOf(true) }
        var password by remember { mutableStateOf("brave-otter-92") }
        var searchQuery by remember { mutableStateOf("") }
        var dropdownExpanded by remember { mutableStateOf(true) }
        var selectedModel by remember { mutableIntStateOf(0) }
        val modelOptions = listOf(
            "Claude Sonnet 4.5",
            "GPT-5",
            "Gemini 2.5 Pro",
            "Junie Reasoner",
            "VS Code Pro",
            "Anthropic Haiku",
            "OpenAI o4-mini",
            "Gemini Flash",
            "Mistral Medium",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoButton(
                    label = "Connect",
                    icon = TennyoIcons.Link,
                    size = TennyoButtonSize.Medium,
                    onClick = {},
                )
                TennyoButton(
                    label = "Pair a computer",
                    style = TennyoButtonStyle.Secondary,
                    size = TennyoButtonSize.Medium,
                    onClick = {},
                )
                TennyoButton(
                    label = "End session",
                    style = TennyoButtonStyle.Danger,
                    size = TennyoButtonSize.Small,
                    onClick = {},
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoButton(
                    label = "Launch",
                    style = TennyoButtonStyle.Success,
                    icon = TennyoIcons.PlayArrow,
                    size = TennyoButtonSize.PrimaryAction,
                    onClick = {},
                )
                TennyoButton(
                    label = "Reply",
                    style = TennyoButtonStyle.Tonal,
                    onClick = {},
                )
                TennyoButton(
                    label = "Connect",
                    enabled = false,
                    onClick = {},
                )
            }
            TennyoSegmentedTabs(
                items = listOf("Files", "Launch", "Terminal", "Git"),
                selectedIndex = selectedTab,
                onSelected = { selectedTab = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoTag("Junie AI")
                TennyoStatusChip("Connected", tone = TennyoStatusTone.Connected)
                TennyoStatusChip("Failed", tone = TennyoStatusTone.Failed, showDot = false)
                TennyoStatusChip("Reconnecting", tone = TennyoStatusTone.Reconnecting)
            }
            TennyoProjectRow(
                title = "demo",
                subtitle = "Connected - 14 Jun 2026",
                selected = true,
                onClick = {},
            )
            TennyoGitFileRow(
                status = "M",
                fileName = "UserService.kt",
                changedLines = "src/main/kotlin/src/main/kotlin/src/main/kotlin/src/main/kotlin",
                addedLines = 24,
                deletedLines = 3,
                selected = true,
                onClick = {},
            )
            var tabIndex by remember { mutableStateOf(0) }
            TennyoEditorTabs(
                tabs = listOf(
                    TennyoEditorTab("UserService.kt", icon = fileTabIcon("UserService.kt")),
                    TennyoEditorTab("build.gradle.kts", icon = fileTabIcon("build.gradle.kts"), dirty = tabIndex == 1),
                    TennyoEditorTab(
                        "SettingsScreen.kt",
                        icon = fileTabIcon("SettingsScreen.kt"),
                        dirty = tabIndex == 1,
                    ),
                ),
                selectedIndex = tabIndex,
                onSelected = { tabIndex = it },
                leadingAction = {
                    TennyoTabStripButton(
                        imageVector = TennyoIcons.Folder,
                        contentDescription = "Open navigator",
                        onClick = {},
                    )
                },
                trailingAction = {
                    TennyoTabStripButton(imageVector = TennyoIcons.Add, onClick = {})
                },
            )
            TennyoEditorTabs(
                tabs = listOf(
                    TennyoEditorTab("zsh", icon = TennyoIcons.Terminal),
                    TennyoEditorTab("gradle", icon = TennyoIcons.Terminal),
                    TennyoEditorTab("tests", icon = TennyoIcons.Terminal),
                ),
                selectedIndex = 1,
                onSelected = {},
                onClose = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TennyoFocusedTextField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.weight(1f),
                )
                TennyoSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search launch configurations",
                    modifier = Modifier.weight(1f),
                )
            }
            TennyoDropdownMenuPreview(
                selectedLabel = modelOptions[selectedModel],
                options = modelOptions,
                selectedIndex = selectedModel,
                onSelected = { selectedModel = it },
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier.fillMaxWidth(),
                maxMenuHeight = 180.dp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TennyoSwitch(checked = switchChecked, onCheckedChange = { switchChecked = it })
                TennyoSwitch(checked = false, onCheckedChange = {})
                TennyoSwitch(checked = true, onCheckedChange = {}, enabled = false)
                TennyoSwitch(checked = false, onCheckedChange = {}, enabled = false)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TennyoAccessoryKey("esc", onClick = {})
                TennyoAccessoryKey("tab", onClick = {})
                TennyoAccessoryKey("ctrl", selected = true, toggle = true, onClick = {})
                TennyoAccessoryKey("shift", toggle = true, onClick = {})
                TennyoAccessoryKey("↑", onClick = {})
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TennyoAccessoryKey("⌫", contentDescription = "Backspace", onClick = {})
                TennyoAccessoryKey("⏎", contentDescription = "Enter", emphasized = true, onClick = {})
                TennyoKeyCap("PgUp")
                TennyoKeyCap("Ctrl+Alt+D")
            }
            TennyoWorkingIndicator(label = "Working...")
            TennyoBottomSheetHeader(
                title = "Remote session",
                actionLabel = "End",
                onAction = {},
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TennyoProjectAvatar("codex", selected = true)
                TennyoProjectAvatar("claude")
                TennyoProjectAvatar("copilot")
                TennyoConnectionIcon(TennyoStatusTone.Connected)
                TennyoConnectionIcon(TennyoStatusTone.Reconnecting)
                TennyoConnectionIcon(TennyoStatusTone.Failed)
            }
            TennyoAppBrand(label = "Tennyo")
            TennyoCodeSurface {
                TennyoCodeText("@Service", tone = TennyoCodeTone.Annotation)
                TennyoCodeText("class UserService(val repo: Repo) {", tone = TennyoCodeTone.Keyword)
                TennyoCodeText("  fun count(): Int = 42", tone = TennyoCodeTone.Function)
                TennyoCodeText("}")
            }
            TennyoUnifiedDiffSurface(
                rows = listOf(
                    TennyoUnifiedDiffRow(12, 12, " ", "fun delete(id: Long) {"),
                    TennyoUnifiedDiffRow(13, null, "−", "    repo.deleteById(id)", removed = true),
                    TennyoUnifiedDiffRow(
                        null,
                        13,
                        "+",
                        "    if (exists(id)) remove(id) // deliberately long editor line that scrolls horizontally",
                        added = true,
                    ),
                    TennyoUnifiedDiffRow(14, 14, " ", "}"),
                ),
                modifier = Modifier.fillMaxWidth().height(112.dp),
            )
            TennyoStatusBanner(
                title = "Reconnecting",
                message = "Reconnecting…",
                tone = TennyoStatusTone.Reconnecting,
                actionLabel = "Try again",
                onAction = {},
            )
        }
    }
}

@Composable
private fun TennyoDropdownMenuPreview(
    selectedLabel: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    maxMenuHeight: Dp = 180.dp,
) {
    TennyoDropdownMenu(
        selectedLabel = selectedLabel,
        options = options,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        enabled = true,
        maxMenuHeight = maxMenuHeight,
    )
    Spacer(Modifier.height(maxMenuHeight))
}

