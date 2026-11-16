package app.tennyo.mobile.feature.files

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.LaunchCandidate
import app.tennyo.mobile.design.SavePhase
import app.tennyo.mobile.design.TabCloseMode
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.TennyoSaveButton
import app.tennyo.mobile.design.editor.CodeEditor
import app.tennyo.mobile.design.editor.ProjectFilesEditorPanel
import app.tennyo.mobile.design.editor.detectEditorLanguage
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.files.generated.resources.Res
import app.tennyo.mobile.feature.files.generated.resources.file_action_launch
import app.tennyo.mobile.feature.files.generated.resources.file_action_redo
import app.tennyo.mobile.feature.files.generated.resources.file_action_save
import app.tennyo.mobile.feature.files.generated.resources.file_action_saved
import app.tennyo.mobile.feature.files.generated.resources.file_action_saving
import app.tennyo.mobile.feature.files.generated.resources.file_action_undo
import app.tennyo.mobile.feature.files.generated.resources.file_choose_launch
import app.tennyo.mobile.feature.files.generated.resources.file_launch_close
import app.tennyo.mobile.feature.files.generated.resources.file_launch_started
import app.tennyo.mobile.feature.files.generated.resources.file_launch_stop
import app.tennyo.mobile.feature.files.generated.resources.file_remote_conflict
import app.tennyo.mobile.feature.files.generated.resources.file_select_to_edit
import app.tennyo.mobile.feature.files.generated.resources.file_unavailable
import app.tennyo.mobile.feature.files.generated.resources.files_title
import app.tennyo.mobile.link.pane.nowEpochMillis
import org.jetbrains.compose.resources.stringResource

/** The files pane of [component]: its editor tabs and the selected file's editor. */
@Composable
fun FilesPane(
    component: FilesPaneComponent,
    panePadding: PanePadding,
    keyboardVisible: Boolean,
    onNavigatorOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    FilesPane(
        state = state,
        onIntent = component::onIntent,
        panePadding = panePadding,
        keyboardVisible = keyboardVisible,
        onNavigatorOpenChange = onNavigatorOpenChange,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilesPane(
    state: FilesState,
    onIntent: (FilesIntent) -> Unit,
    panePadding: PanePadding,
    keyboardVisible: Boolean,
    onNavigatorOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLaunchChoices by remember { mutableStateOf(false) }
    var activeLaunch by remember { mutableStateOf<LaunchCandidate?>(null) }
    val selectedFilePath = state.selectedFile?.path
    val launchCandidates = state.launchCandidates.takeIf { state.launchCandidatePath == selectedFilePath }.orEmpty()
    LaunchedEffect(selectedFilePath) {
        if (selectedFilePath != null && state.contentNeedsRefresh(selectedFilePath, nowEpochMillis())) {
            onIntent(FilesIntent.RefreshSelectedFile)
        }
    }

    ProjectFilesEditorPanel(
        tabs = state.openedFiles,
        selectedTabIndex = state.safeSelectedTabIndex,
        itemKey = { it.path },
        title = { it.name },
        isDirty = { it.isDirty },
        onSelectTab = { index, _ -> onIntent(FilesIntent.SelectTab(index)) },
        onCloseTab = { file -> onIntent(FilesIntent.CloseTab(file.path)) },
        closeMode = if (EditorTabAction.CLOSE in state.allowedTabActions) {
            TabCloseMode.SelectedOnly
        } else {
            TabCloseMode.Never
        },
        onNavigatorOpenChange = onNavigatorOpenChange,
        keyboardVisible = keyboardVisible,
        contentHorizontalPadding = panePadding.contentHorizontalPadding,
        bottomContentInset = 0.dp,
        openNavigatorContentDescription = stringResource(Res.string.files_title),
        editorActions = state.selectedFile?.let { file ->
            {
                TennyoIconButton(
                    icon = TennyoIcons.Undo,
                    contentDescription = stringResource(Res.string.file_action_undo),
                    onClick = { onIntent(FilesIntent.Undo) },
                    enabled = file.viewable && state.canUndo(file.path),
                )
                TennyoIconButton(
                    icon = TennyoIcons.Redo,
                    contentDescription = stringResource(Res.string.file_action_redo),
                    onClick = { onIntent(FilesIntent.Redo) },
                    enabled = file.viewable && state.canRedo(file.path),
                )
                if (launchCandidates.isNotEmpty()) {
                    TennyoIconButton(
                        icon = TennyoIcons.PlayArrow,
                        contentDescription = stringResource(Res.string.file_action_launch),
                        onClick = {
                            if (launchCandidates.size == 1) {
                                launchCandidates.single().also { candidate ->
                                    activeLaunch = candidate
                                    onIntent(FilesIntent.LaunchConfiguration(candidate.configurationId))
                                }
                            } else {
                                showLaunchChoices = true
                            }
                        },
                        enabled = file.viewable,
                    )
                }
                Spacer(Modifier.weight(1f))
                SaveButton(
                    file = file,
                    onClick = { onIntent(FilesIntent.SaveSelectedFile) },
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .padding(panePadding.contentPadding),
    ) {
        Crossfade(
            targetState = state.selectedFile?.path,
            label = "Remote editor",
            modifier = Modifier.fillMaxSize(),
        ) { selectedPath ->
            SelectedFileContent(
                state = state,
                file = selectedPath?.let(state::file),
                keyboardVisible = keyboardVisible,
                onEdit = { path, text -> onIntent(FilesIntent.EditText(path, text)) },
            )
        }
    }

    if (showLaunchChoices && launchCandidates.size > 1) {
        ModalBottomSheet(onDismissRequest = { showLaunchChoices = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.large)) {
                TennyoBottomSheetHeader(
                    title = stringResource(Res.string.file_choose_launch),
                    actionLabel = stringResource(Res.string.file_launch_close),
                    onAction = { showLaunchChoices = false },
                )
                launchCandidates.forEach { candidate ->
                    TennyoButton(
                        label = candidate.name,
                        onClick = {
                            showLaunchChoices = false
                            activeLaunch = candidate
                            onIntent(FilesIntent.LaunchConfiguration(candidate.configurationId))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.large, vertical = Spacing.small),
                    )
                }
            }
        }
    }

    activeLaunch?.let { candidate ->
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.large)) {
                TennyoBottomSheetHeader(
                    title = stringResource(Res.string.file_action_launch),
                    actionLabel = stringResource(Res.string.file_launch_close),
                    onAction = { activeLaunch = null },
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                    Text(candidate.type, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(Res.string.file_launch_started), style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    TennyoButton(
                        label = stringResource(Res.string.file_launch_stop),
                        onClick = {
                            onIntent(FilesIntent.StopLaunchConfiguration(candidate.configurationId))
                            activeLaunch = null
                        },
                        icon = TennyoIcons.Stop,
                        style = TennyoButtonStyle.Danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedFileContent(
    state: FilesState,
    file: OpenedFile?,
    keyboardVisible: Boolean,
    onEdit: (path: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            file == null -> Text(text = stringResource(Res.string.file_select_to_edit))

            file.viewable -> Crossfade(
                targetState = state.selectedContentShowsLoader(nowEpochMillis()),
                modifier = Modifier.fillMaxSize(),
                label = "Remote editor content",
            ) { loading ->
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TennyoProgressIndicator()
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (file.conflictingHostText != null) {
                            Text(
                                text = stringResource(Res.string.file_remote_conflict),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.small),
                            )
                        }
                        CodeEditor(
                            value = file.text,
                            onValueChange = { onEdit(file.path, it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            readOnly = !file.writable,
                            fileId = file.path,
                            language = detectEditorLanguage(file.name, textSample = file.text),
                            keyboardVisible = keyboardVisible,
                        )
                    }
                }
            }

            else -> Text(
                text = file.unavailableReason ?: stringResource(Res.string.file_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveButton(
    file: OpenedFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoSaveButton(
        phase = file.savePhase(),
        countdown = file.autosave,
        onClick = onClick,
        saveLabel = stringResource(Res.string.file_action_save),
        savingLabel = stringResource(Res.string.file_action_saving),
        savedLabel = stringResource(Res.string.file_action_saved),
        modifier = modifier,
    )
}

/** What the save button shows: a file the phone cannot write reads as saved, with nothing to do. */
internal fun OpenedFile.savePhase(): SavePhase = when {
    syncState == FileSyncState.SAVING -> SavePhase.SAVING
    viewable && writable && isDirty -> SavePhase.UNSAVED
    else -> SavePhase.SAVED
}

@Preview(widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun FilesPanePreview() {
    TennyoTheme {
        FilesPane(
            state = previewFilesState(),
            onIntent = {},
            panePadding = panePadding(
                innerPadding = PaddingValues(),
                layoutDirection = LayoutDirection.Ltr,
                bottomSystemBarInset = 0.dp,
            ),
            keyboardVisible = false,
            onNavigatorOpenChange = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
