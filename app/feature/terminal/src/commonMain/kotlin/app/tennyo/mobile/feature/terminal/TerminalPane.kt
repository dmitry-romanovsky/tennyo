package app.tennyo.mobile.feature.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.mobile.design.TabCloseMode
import app.tennyo.mobile.design.TennyoEditorTab
import app.tennyo.mobile.design.TennyoEditorTabs
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.TennyoTabStripButton
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.PaneTabStrip
import app.tennyo.mobile.design.layout.isScreenLandscape
import app.tennyo.mobile.design.layout.paneInset
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_default_title
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_new
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_no_tabs
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_output_placeholder
import app.tennyo.mobile.link.pane.nowEpochMillis
import org.jetbrains.compose.resources.stringResource


/** The terminal pane of [component]: terminal tabs, the selected terminal screen and the key bar. */
@Composable
fun TerminalPane(
    component: TerminalPaneComponent,
    panePadding: PanePadding,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val keyBar by component.keyBar.collectAsState()
    TerminalPane(
        state = state,
        keyBar = keyBar,
        onIntent = component::onIntent,
        panePadding = panePadding,
        keyboardVisible = keyboardVisible,
        modifier = modifier,
    )
}

@Composable
fun TerminalPane(
    state: TerminalState,
    keyBar: TerminalKeyBarSettings,
    onIntent: (TerminalIntent) -> Unit,
    panePadding: PanePadding,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeFieldText by remember { mutableStateOf("") }
    var imeField by remember { mutableStateOf(TextFieldValue("")) }
    var latched by remember { mutableStateOf(TerminalModifiers()) }
    var addHotkeySheetVisible by rememberSaveable { mutableStateOf(false) }
    val keyBarRows = if (isScreenLandscape()) 1 else 2
    val keyBarLayout = remember(keyBar, keyBarRows) { terminalKeyBarLayout(keyBar, keyBarRows) }
    val terminalSnapshot = state.selectedTerminalSnapshot()
    fun focusTerminalInput() {
        focusManager.clearFocus(force = true)
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    LaunchedEffect(state.selectedTerminal?.id) {
        imeFieldText = ""
        imeField = TextFieldValue("")
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(panePadding.contentPadding),
    ) {
        TerminalTabStrip(
            state = state,
            keyboardVisible = keyboardVisible,
            horizontalPadding = panePadding.contentHorizontalPadding,
            onCreate = { onIntent(TerminalIntent.Create) },
            onIntent = onIntent,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .paneInset()
                .clip(if (state.tabs.isNotEmpty() && !keyboardVisible) Corners.panelBottom else Corners.panel),
        ) {
            TerminalScreen(
                state = state,
                fallbackSnapshot = terminalSnapshot,
                onTap = ::focusTerminalInput,
                modifier = Modifier.fillMaxSize(),
            )
            BasicTextField(
                value = imeField,
                onValueChange = { newTextFieldValue ->
                    val delta = computeTerminalImeDelta(imeFieldText, newTextFieldValue.text)
                    if (delta.inputDelta.isNotEmpty()) {
                        val input = delta.inputDelta.toTerminalInput(latched)
                        if (input.isNotEmpty()) {
                            onIntent(TerminalIntent.SendInput(input))
                        }
                    }
                    imeFieldText = rebaseTerminalImeField(delta.newFieldText)
                    imeField = newTextFieldValue.copy(text = imeFieldText)
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            keyboardController?.show()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace) {
                            onIntent(TerminalIntent.SendInput(IME_BACKSPACE_INPUT))
                            true
                        } else {
                            false
                        }
                    },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                ),
                singleLine = false,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.extraSmall))
        TerminalKeyBar(
            layout = keyBarLayout,
            latched = latched,
            onLatchedChange = { latched = it },
            onInput = { text ->
                onIntent(TerminalIntent.SendInput(text))
            },
            onAddHotkey = { addHotkeySheetVisible = true },
            contentPadding = PaddingValues(horizontal = panePadding.contentHorizontalPadding),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // The sheet opens in place: navigating to Settings would stop this screen and start background idle.
    if (addHotkeySheetVisible) {
        AddTerminalHotkeySheet(
            keyBar = keyBar,
            onAdd = { hotkey -> onIntent(TerminalIntent.AddHotkey(hotkey)) },
            onDismiss = { addHotkeySheetVisible = false },
        )
    }
}

private data class TerminalContentTarget(
    val terminalId: String?,
    val showBlockingLoader: Boolean,
)

/** The screen of the selected terminal, or a spinner while it has nothing to show yet. */
@Composable
private fun TerminalScreen(
    state: TerminalState,
    fallbackSnapshot: TerminalSnapshot,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = nowEpochMillis()
    val showBlockingLoader = state.selectedSnapshotShowsLoader(now)
    val contentTarget = TerminalContentTarget(
        terminalId = state.selectedTerminal?.id,
        showBlockingLoader = showBlockingLoader,
    )
    Surface(
        modifier = modifier,
        color = TERMINAL_BACKGROUND,
    ) {
        Crossfade(
            targetState = contentTarget,
            modifier = Modifier.fillMaxSize(),
            label = "Remote terminal output",
        ) { target ->
            if (target.showBlockingLoader) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TennyoProgressIndicator()
                }
            } else {
                val targetSnapshot = target.terminalId
                    ?.let { id ->
                        state.snapshots[id]
                            ?: state.tabs.firstOrNull { it.id == id }?.snapshot
                    }
                    ?: fallbackSnapshot
                TerminalConsoleText(
                    terminalId = target.terminalId.orEmpty(),
                    snapshot = targetSnapshot,
                    placeholder = stringResource(Res.string.terminal_output_placeholder),
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onTap)
                        .focusable(),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TerminalTabStrip(
    state: TerminalState,
    keyboardVisible: Boolean,
    horizontalPadding: Dp,
    onCreate: () -> Unit,
    onIntent: (TerminalIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultTerminalTitle = stringResource(Res.string.terminal_default_title)
    if (state.tabs.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(Res.string.terminal_no_tabs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TennyoIconButton(
                icon = TennyoIcons.Add,
                contentDescription = stringResource(Res.string.terminal_new),
                onClick = onCreate,
            )
        }
    } else {
        AnimatedVisibility(visible = !keyboardVisible, modifier = modifier) {
            PaneTabStrip {
                TennyoEditorTabs(
                    tabs = state.tabs.map { terminal ->
                        TennyoEditorTab(
                            title = terminal.title.ifBlank { defaultTerminalTitle },
                            icon = TennyoIcons.Terminal,
                            key = terminal.id,
                        )
                    },
                    selectedIndex = state.safeSelectedIndex,
                    onSelected = { index ->
                        state.tabs.getOrNull(index)?.let { terminal ->
                            onIntent(TerminalIntent.Select(terminal.id))
                        }
                    },
                    onClose = { index ->
                        state.tabs.getOrNull(index)?.let { terminal ->
                            onIntent(TerminalIntent.Close(terminal.id))
                        }
                    },
                    closeMode = TabCloseMode.Always,
                    modifier = Modifier.fillMaxWidth(),
                    trailingAction = {
                        TennyoTabStripButton(
                            imageVector = TennyoIcons.Add,
                            contentDescription = stringResource(Res.string.terminal_new),
                        ) {
                            onCreate()
                        }
                    },
                )
            }
        }
    }
}

// Hardware and IME Backspace ignore latched modifiers, unlike the key bar's ⌫.
private val IME_BACKSPACE_INPUT = TerminalKeyChord(NamedTerminalKey.Backspace).encode()

@Preview(widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun TerminalPanePortraitPreview() {
    TerminalPanePreview()
}

@Preview(widthDp = 900, heightDp = 420, showBackground = true)
@Composable
private fun TerminalPaneLandscapePreview() {
    TerminalPanePreview()
}

@Composable
private fun TerminalPanePreview() {
    TennyoTheme {
        TerminalPane(
            state = previewTerminalState(),
            keyBar = TerminalKeyBarSettings(
                customHotkeys = listOf(
                    CustomTerminalHotkey(TerminalKeyChord('d', TerminalModifiers(ctrl = true)), "EOF"),
                    CustomTerminalHotkey(TerminalKeyChord('b', TerminalModifiers(alt = true)), "Word back"),
                ),
            ),
            onIntent = {},
            panePadding = panePadding(
                innerPadding = PaddingValues(),
                layoutDirection = LayoutDirection.Ltr,
                bottomSystemBarInset = 0.dp,
            ),
            keyboardVisible = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
