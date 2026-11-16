package app.tennyo.mobile.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.contract.Capability
import app.tennyo.mobile.design.ErrorDetailsSheet
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.layout.LocalKeyboardVisible
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.ProjectWorkspaceScaffold
import app.tennyo.mobile.design.layout.WorkspaceNavigationStrip
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.chat.ChatConversationScreen
import app.tennyo.mobile.feature.chat.ChatPane
import app.tennyo.mobile.feature.chat.ChatPaneComponent
import app.tennyo.mobile.feature.files.FilesNavigator
import app.tennyo.mobile.feature.files.FilesPane
import app.tennyo.mobile.feature.git.GitPane
import app.tennyo.mobile.feature.git.GitUnavailablePane
import app.tennyo.mobile.feature.launch.LaunchPane
import app.tennyo.mobile.feature.terminal.TerminalPane
import app.tennyo.mobile.generated.resources.Res
import app.tennyo.mobile.generated.resources.action_failure_title
import app.tennyo.mobile.generated.resources.connecting_description
import app.tennyo.mobile.generated.resources.connecting_title
import app.tennyo.mobile.generated.resources.remote_project_title
import app.tennyo.mobile.generated.resources.show_full_error
import app.tennyo.mobile.generated.resources.stats_end_session
import app.tennyo.mobile.generated.resources.unavailable_description
import app.tennyo.mobile.generated.resources.unavailable_title
import app.tennyo.mobile.generated.resources.waiting_approval_description
import app.tennyo.mobile.generated.resources.waiting_approval_title
import app.tennyo.mobile.link.LinkTicket
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.jetbrains.compose.resources.stringResource

/** The workspace of [component]: connection chrome around the pairing step, the waiting states, or the panes. */
@Composable
fun WorkspaceScreen(
    component: WorkspaceComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val pages by component.panes.subscribeAsState()
    val layoutDirection = LocalLayoutDirection.current

    var showStats by remember { mutableStateOf(false) }
    var fullErrorText by remember { mutableStateOf<String?>(null) }
    var navigatorOpen by remember { mutableStateOf(false) }

    val children = pages.items.mapNotNull { it.instance }
    val files = children.firstNotNullOfOrNull { it as? WorkspacePaneChild.Files }?.component
    val git = children.firstNotNullOfOrNull { it as? WorkspacePaneChild.Git }?.component
    val gitHasNestedDestination = git?.state?.collectAsState()?.value?.hasNestedDestination == true
    val chat = children.firstNotNullOfOrNull { it as? WorkspacePaneChild.Chat }?.component

    PlatformWorkspaceBackHandler(
        enabled = state.selectedPane == WorkspacePaneKind.GIT && gitHasNestedDestination,
        onBack = component::onBack,
    )

    LaunchedEffect(component, showStats) {
        component.onIntent(WorkspaceIntent.StatsVisibilityChanged(showStats))
    }
    DisposableEffect(component) {
        onDispose { component.onIntent(WorkspaceIntent.StatsVisibilityChanged(open = false)) }
    }

    WorkspaceSheets(
        component = component,
        state = state,
        showStats = showStats,
        onStatsDismissed = { showStats = false },
        fullErrorText = fullErrorText,
        onFullErrorDismissed = { fullErrorText = null },
    )

    // Destinations are always there — an unserved one shows why — so only panels depend on what the host serves.
    val availablePanes = WorkspacePaneKind.entries.filter { pane ->
        pane.destination != null || pane.capability == null || state.hasCapability(pane.capability)
    }
    val selectedPane = state.selectedPane.takeIf { it in availablePanes } ?: availablePanes.first()
    val screenStep = state.screenStep
    val chrome = WorkspaceChromeState.of(state.capabilities, state.lastDestination, state.openPanel)
    var panelsSheetOpen by remember { mutableStateOf(false) }

    WorkspacePanelsSheetHost(
        open = panelsSheetOpen,
        chrome = chrome,
        onIntent = component::onIntent,
        onDismiss = { panelsSheetOpen = false },
    )

    LaunchedEffect(screenStep, selectedPane) {
        if (screenStep != WorkspaceStep.Connected || selectedPane != WorkspacePaneKind.FILES) navigatorOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        ProjectWorkspaceScaffold(
            title = stringResource(Res.string.remote_project_title, state.projectName),
            navigationStrip = if (screenStep == WorkspaceStep.Connected) {
                {
                    WorkspaceStrip(
                        chrome = chrome,
                        state = state,
                        onIntent = component::onIntent,
                        onOverflowClick = {
                            // A lone panel has nothing to choose between, so its button opens it without the sheet.
                            val lonePanel = chrome.panels.singleOrNull()
                            if (lonePanel != null) {
                                component.onIntent(WorkspaceIntent.OpenPanel(lonePanel))
                            } else {
                                panelsSheetOpen = true
                            }
                        },
                    )
                }
            } else {
                null
            },
            navigatorOpen = navigatorOpen,
            onNavigatorOpenChange = { navigatorOpen = it },
            navigatorContent = { closeDrawer ->
                if (screenStep == WorkspaceStep.Connected && selectedPane == WorkspacePaneKind.FILES && files != null) {
                    FilesNavigator(component = files, onFileOpened = closeDrawer)
                }
            },
            onBack = component::onBack,
            actions = { ConnectionStatusButton(state = state, onClick = { showStats = true }) },
            banner = {
                Column {
                    ConnectionBanner(
                        state = state,
                        onIntent = component::onIntent,
                        visible = screenStep == WorkspaceStep.Connected,
                        onShowFullMessage = { showStats = true },
                    )
                }
            },
        ) { innerPadding ->
            val keyboardVisible = LocalKeyboardVisible.current
            val padding = panePadding(
                innerPadding = innerPadding,
                layoutDirection = layoutDirection,
                bottomSystemBarInset = safeDrawingBottomInset(),
            )
            Crossfade(targetState = screenStep, label = "Workspace step", modifier = Modifier.fillMaxSize()) { step ->
                WorkspaceStepContent(
                    step = step,
                    state = state,
                    onIntent = component::onIntent,
                    padding = padding,
                    onShowFullError = { fullErrorText = it },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Crossfade(selectedPane, label = "Workspace pane", modifier = Modifier.fillMaxSize()) { pane ->
                            val child = pages.items.getOrNull(pane.ordinal)?.instance
                            PaneContent(
                                child = child,
                                gitServed = Capability.GIT in state.capabilities,
                                padding = padding,
                                keyboardVisible = keyboardVisible,
                                onNavigatorOpenChange = { navigatorOpen = it },
                            )
                        }
                        state.actionFailure?.let { failure ->
                            TennyoStatusBanner(
                                title = stringResource(Res.string.action_failure_title),
                                message = failure.resolve(),
                                tone = TennyoStatusTone.Failed,
                                icon = TennyoIcons.ErrorOutline,
                                onDismiss = { component.onIntent(WorkspaceIntent.DismissActionFailure) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(padding.contentPadding)
                                    .padding(Spacing.medium),
                            )
                        }
                    }
                }
            }
        }
        chat?.let { ChatConversationLayer(it, state, onBack = component::onBack) }
    }
}

/** The conversation is a screen of its own, sliding over the workspace chrome instead of living in a pane. */
@Composable
private fun ChatConversationLayer(chat: ChatPaneComponent, state: WorkspaceState, onBack: () -> Unit) {
    val inConversation = chat.state.collectAsState().value.inConversation
    val open = inConversation &&
        state.screenStep == WorkspaceStep.Connected &&
        state.selectedPane == WorkspacePaneKind.CHAT
    PlatformWorkspaceBackHandler(enabled = open, onBack = onBack)
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
    ) {
        ChatConversationScreen(component = chat, modifier = Modifier.fillMaxSize())
    }
}

/** The stats and full-error sheets over the workspace; each shows while its state says so. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSheets(
    component: WorkspaceComponent,
    state: WorkspaceState,
    showStats: Boolean,
    onStatsDismissed: () -> Unit,
    fullErrorText: String?,
    onFullErrorDismissed: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    if (showStats) {
        ModalBottomSheet(
            onDismissRequest = onStatsDismissed,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            StatsSheet(state, component::onIntent)
        }
    }
    fullErrorText?.let { errorText ->
        ModalBottomSheet(
            onDismissRequest = onFullErrorDismissed,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ErrorDetailsSheet(
                errorText = errorText,
                onCopy = { clipboardManager.setText(AnnotatedString(errorText)) },
            )
        }
    }
}

@Composable
private fun PaneContent(
    child: WorkspacePaneChild?,
    gitServed: Boolean,
    padding: PanePadding,
    keyboardVisible: Boolean,
    onNavigatorOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (child) {
        is WorkspacePaneChild.Chat -> ChatPane(child.component, padding, modifier)
        is WorkspacePaneChild.Files ->
            FilesPane(child.component, padding, keyboardVisible, onNavigatorOpenChange, modifier)
        is WorkspacePaneChild.Launch -> LaunchPane(child.component, padding, modifier)
        is WorkspacePaneChild.Terminal -> TerminalPane(child.component, padding, keyboardVisible, modifier)
        is WorkspacePaneChild.Git ->
            if (gitServed) GitPane(child.component, padding, modifier) else GitUnavailablePane(modifier)
        null -> Unit
    }
}

/** What the workspace shows before and around the panes: connecting, waiting for approval, pairing, unavailable. */
@Composable
private fun WorkspaceStepContent(
    step: WorkspaceStep,
    state: WorkspaceState,
    onIntent: (WorkspaceIntent) -> Unit,
    padding: PanePadding,
    onShowFullError: (String) -> Unit,
    modifier: Modifier = Modifier,
    panes: @Composable () -> Unit,
) {
    val contentModifier = modifier.padding(padding.contentPadding)
    when (step) {
        WorkspaceStep.Initiating -> WaitingPanel(
            title = stringResource(Res.string.connecting_title),
            description = stringResource(Res.string.connecting_description),
            modifier = contentModifier,
        )

        WorkspaceStep.WaitingForIde -> WaitingPanel(
            title = stringResource(Res.string.waiting_approval_title),
            description = stringResource(Res.string.waiting_approval_description),
            modifier = contentModifier,
        )

        WorkspaceStep.Pairing -> state.pairingConfirmation?.let {
            PairingPanel(
                confirmation = it,
                onToggleWord = { word -> onIntent(WorkspaceIntent.TogglePairingWord(word)) },
                onClear = { onIntent(WorkspaceIntent.ClearPairingWords) },
                onConfirm = { onIntent(WorkspaceIntent.ConfirmPairing) },
                modifier = contentModifier,
            )
        } ?: WaitingPanel(
            title = stringResource(Res.string.waiting_approval_title),
            description = stringResource(Res.string.waiting_approval_description),
            modifier = contentModifier,
        )

        WorkspaceStep.Connected -> panes()

        WorkspaceStep.Unavailable -> {
            val unexpectedError = state.unexpectedConnectionError
            val issueText = (unexpectedError?.issue ?: state.issue)?.text?.resolve()
            WaitingPanel(
                title = stringResource(Res.string.unavailable_title),
                description = issueText ?: stringResource(Res.string.unavailable_description),
                modifier = contentModifier,
                onEndSession = { onIntent(WorkspaceIntent.EndSession) },
                onShowFullError = unexpectedError?.let { { onShowFullError("$issueText\n\n${it.stackTrace}") } },
            )
        }
    }
}

@Composable
private fun WaitingPanel(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onEndSession: (() -> Unit)? = null,
    onShowFullError: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.extraLarge),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (onShowFullError != null) 3 else 10,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onEndSession != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        TennyoButton(
                            label = stringResource(Res.string.stats_end_session),
                            onClick = onEndSession,
                            style = TennyoButtonStyle.Danger,
                            size = TennyoButtonSize.Small,
                        )
                        if (onShowFullError != null) {
                            TennyoButton(
                                label = stringResource(Res.string.show_full_error),
                                onClick = onShowFullError,
                                style = TennyoButtonStyle.Text,
                                size = TennyoButtonSize.Small,
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(30.dp, 250.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TennyoProgressIndicator()
                }
            }
        }
    }
}

@Preview(widthDp = 420, heightDp = 900)
@Composable
private fun WorkspaceConnectingPreview() {
    TennyoTheme {
        WorkspaceStepContent(
            step = WorkspaceStep.WaitingForIde,
            state = WorkspaceState(sessionTicket = PREVIEW_TICKET, projectName = "Tennyo"),
            onIntent = {},
            padding = panePadding(PaddingValues(), androidx.compose.ui.unit.LayoutDirection.Ltr, 0.dp),
            onShowFullError = {},
            modifier = Modifier.fillMaxSize(),
        ) {}
    }
}

/** A ticket shaped like a real one, for previews only. */
private val PREVIEW_TICKET = LinkTicket(
    endpoint = "https://192.0.2.10:8443",
    sessionId = "preview-session",
    token = "preview-token",
    fingerprint = "preview-fingerprint",
    projectName = "Tennyo",
    projectPath = "~/tennyo",
)

/** The destination strip for [chrome], with the overflow only where the host serves a panel to put behind it. */
@Composable
private fun WorkspaceStrip(
    chrome: WorkspaceChromeState,
    state: WorkspaceState,
    onIntent: (WorkspaceIntent) -> Unit,
    onOverflowClick: () -> Unit,
) {
    WorkspaceNavigationStrip(
        destinations = chrome.destinations.map { it.toItem(state) },
        selectedKey = chrome.highlightedDestination?.name,
        onDestinationSelected = { key ->
            WorkspaceDestination.entries.firstOrNull { it.name == key }
                ?.let { onIntent(WorkspaceIntent.SelectDestination(it)) }
        },
        openPanelIcon = chrome.openPanel?.icon(),
        singlePanelIcon = chrome.panels.singleOrNull()?.icon(),
        singlePanelLabel = chrome.panels.singleOrNull()?.label(),
        onOverflowClick = onOverflowClick.takeIf { chrome.overflowVisible },
    )
}

/** Shows the panels sheet while [open]; picking a panel opens it and closes the sheet. */
@Composable
private fun WorkspacePanelsSheetHost(
    open: Boolean,
    chrome: WorkspaceChromeState,
    onIntent: (WorkspaceIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    WorkspacePanelsSheet(
        panels = chrome.panels,
        openPanel = chrome.openPanel,
        onPanelSelected = { panel ->
            onDismiss()
            onIntent(WorkspaceIntent.OpenPanel(panel))
        },
        onDismiss = onDismiss,
    )
}
