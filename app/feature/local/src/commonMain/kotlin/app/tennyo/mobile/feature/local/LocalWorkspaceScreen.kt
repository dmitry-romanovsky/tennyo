package app.tennyo.mobile.feature.local

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.SavePhase
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoSaveButton
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.editor.CodeEditor
import app.tennyo.mobile.design.editor.ProjectFilesEditorPanel
import app.tennyo.mobile.design.editor.detectEditorLanguage
import app.tennyo.mobile.design.files.ProjectFilesBreadcrumb
import app.tennyo.mobile.design.files.ProjectFilesEntry
import app.tennyo.mobile.design.files.ProjectFilesScreen
import app.tennyo.mobile.design.files.ProjectFilesScreenState
import app.tennyo.mobile.design.layout.LocalKeyboardVisible
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.ProjectWorkspaceScaffold
import app.tennyo.mobile.design.layout.WorkspaceDestinationItem
import app.tennyo.mobile.design.layout.WorkspaceNavigationStrip
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.GitPane
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_editor_empty
import app.tennyo.mobile.feature.local.generated.resources.local_editor_loading
import app.tennyo.mobile.feature.local.generated.resources.local_failure_title
import app.tennyo.mobile.feature.local.generated.resources.local_files
import app.tennyo.mobile.feature.local.generated.resources.local_git
import app.tennyo.mobile.feature.local.generated.resources.local_no_files
import app.tennyo.mobile.feature.local.generated.resources.local_redo
import app.tennyo.mobile.feature.local.generated.resources.local_run
import app.tennyo.mobile.feature.local.generated.resources.local_save
import app.tennyo.mobile.feature.local.generated.resources.local_saved_state
import app.tennyo.mobile.feature.local.generated.resources.local_saving
import app.tennyo.mobile.feature.local.generated.resources.local_undo
import org.jetbrains.compose.resources.stringResource

/** A local workspace: the file editor with its navigator drawer, or the Git pane. */
@Composable
fun LocalWorkspaceScreen(component: LocalWorkspaceComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    var closeDrawerSignal by remember { mutableIntStateOf(0) }
    val setNavigatorOpen = { open: Boolean -> component.onIntent(LocalWorkspaceIntent.SetNavigatorOpen(open)) }

    LaunchedEffect(component) {
        component.effects.collect { effect ->
            when (effect) {
                LocalWorkspaceEffect.FileOpened -> closeDrawerSignal++
            }
        }
    }

    val destinations = listOf(
        WorkspaceDestinationItem(
            key = LocalPane.Files.name,
            label = stringResource(Res.string.local_files),
            icon = TennyoIcons.Folder,
        ),
        WorkspaceDestinationItem(
            key = LocalPane.Git.name,
            label = stringResource(Res.string.local_git),
            icon = TennyoIcons.AccountTree,
        ),
    )
    ProjectWorkspaceScaffold(
        title = state.workspace?.name ?: state.slug,
        navigationStrip = {
            WorkspaceNavigationStrip(
                destinations = destinations,
                selectedKey = state.selectedPane.name,
                onDestinationSelected = { key ->
                    LocalPane.entries.firstOrNull { it.name == key }?.let {
                        component.onIntent(LocalWorkspaceIntent.SelectPane(it))
                    }
                },
            )
        },
        onBack = component::onBack,
        modifier = modifier,
        navigatorOpen = state.navigatorOpen,
        onNavigatorOpenChange = setNavigatorOpen,
        navigatorContent = if (state.selectedPane == LocalPane.Files) {
            {
                Column {
                    LocalFilesNavigator(state.navigator, component::onIntent, Modifier.weight(1f))
                    NavigatorCreateActions(
                        onCreate = { name, directory ->
                            component.onIntent(LocalWorkspaceIntent.CreateEntry(name, directory))
                        },
                        modifier = Modifier.padding(vertical = Spacing.medium),
                    )
                }
            }
        } else {
            null
        },
    ) { innerPadding ->
        val keyboardVisible = LocalKeyboardVisible.current
        val installing = state.packages?.takeIf(PackageInstall::showsInSheet)
        if (state.run != null || installing != null) {
            RunOutputSheet(
                run = state.run,
                packages = installing,
                requirementsRejectedLine = state.requirementsRejectedLine,
                onInstallPackage = { component.onIntent(LocalWorkspaceIntent.AskToInstallPackage) },
                onDismiss = {
                    component.onIntent(
                        if (installing is PackageInstall.Failed) {
                            LocalWorkspaceIntent.DismissPackages
                        } else {
                            LocalWorkspaceIntent.DismissRun
                        },
                    )
                },
            )
        }
        PackageDialogs(state.packages, component::onIntent)
        val padding = panePadding(innerPadding, LocalLayoutDirection.current, safeDrawingBottomInset())
        Box(Modifier.fillMaxSize()) {
            LocalPaneContent(state, component, keyboardVisible, closeDrawerSignal, setNavigatorOpen, padding)
            state.failure?.let { failure ->
                TennyoStatusBanner(
                    title = stringResource(Res.string.local_failure_title),
                    message = failure.resolve(),
                    tone = TennyoStatusTone.Failed,
                    onDismiss = { component.onIntent(LocalWorkspaceIntent.DismissFailure) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(padding.contentPadding)
                        .padding(Spacing.medium),
                )
            }
        }
    }
}

/** The two consents of an install: what to look up, then what was found. */
@Composable
private fun PackageDialogs(
    packages: PackageInstall?,
    onIntent: (LocalWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = { onIntent(LocalWorkspaceIntent.DismissPackages) }
    when (packages) {
        is PackageInstall.Asking -> PackageRequestDialog(
            source = packages.source,
            onConfirm = { onIntent(LocalWorkspaceIntent.ConfirmPackageRequest(it)) },
            onDismiss = dismiss,
            modifier = modifier,
        )

        is PackageInstall.Reviewing -> PackageReviewDialog(
            resolution = packages.resolution,
            onConfirm = { onIntent(LocalWorkspaceIntent.ConfirmPackageReview) },
            onDismiss = dismiss,
            modifier = modifier,
        )

        else -> Unit
    }
}

@Composable
private fun LocalPaneContent(
    state: LocalWorkspaceState,
    component: LocalWorkspaceComponent,
    keyboardVisible: Boolean,
    closeDrawerSignal: Int,
    setNavigatorOpen: (Boolean) -> Unit,
    padding: PanePadding,
    modifier: Modifier = Modifier,
) {
    when (state.selectedPane) {
        // Padded like the remote Files pane: the safe-drawing bottom includes the keyboard, so the editor and its
        // action bar sit above it rather than under it.
        LocalPane.Files -> LocalFilesPane(
            state = state,
            onIntent = component::onIntent,
            keyboardVisible = keyboardVisible,
            closeDrawerSignal = closeDrawerSignal,
            onNavigatorOpenChange = setNavigatorOpen,
            modifier = modifier.fillMaxSize().padding(padding.contentPadding),
        )

        LocalPane.Git -> GitPane(
            component = component.git,
            panePadding = padding,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LocalFilesNavigator(
    navigator: LocalNavigatorState,
    onIntent: (LocalWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filesTitle = stringResource(Res.string.local_files)
    val segments = navigator.directory.split('/').filter(String::isNotEmpty)
    ProjectFilesScreen(
        state = ProjectFilesScreenState(
            title = filesTitle,
            breadcrumbs = listOf(ProjectFilesBreadcrumb(id = "", name = filesTitle)) +
                    segments.indices.map { index ->
                        ProjectFilesBreadcrumb(segments.take(index + 1).joinToString("/"), segments[index])
                    },
            parentId = if (segments.isEmpty()) null else segments.dropLast(1).joinToString("/"),
            entries = navigator.entries.map { ProjectFilesEntry(it.path, it.name, it.isDirectory) },
            loading = navigator.loading,
            emptyText = navigator.fault?.text?.resolve() ?: stringResource(Res.string.local_no_files),
        ),
        onDirectoryClick = { onIntent(LocalWorkspaceIntent.OpenDirectory(it)) },
        onFileClick = { onIntent(LocalWorkspaceIntent.OpenFile(it)) },
        modifier = modifier,
    )
}

@Composable
private fun LocalFilesPane(
    state: LocalWorkspaceState,
    onIntent: (LocalWorkspaceIntent) -> Unit,
    keyboardVisible: Boolean,
    closeDrawerSignal: Int,
    onNavigatorOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedFile = state.selectedFile
    val selectedEditor = state.selectedEditor
    ProjectFilesEditorPanel(
        tabs = state.openedFiles,
        selectedTabIndex = state.safeSelectedTab,
        itemKey = { it },
        title = { it.substringAfterLast('/') },
        onSelectTab = { index, _ -> onIntent(LocalWorkspaceIntent.SelectTab(index)) },
        onCloseTab = { onIntent(LocalWorkspaceIntent.CloseFile(it)) },
        onNavigatorOpenChange = onNavigatorOpenChange,
        keyboardVisible = keyboardVisible,
        bottomContentInset = 0.dp,
        closeDrawerSignal = closeDrawerSignal,
        isDirty = { state.editors[it]?.isDirty == true },
        openNavigatorContentDescription = stringResource(Res.string.local_files),
        editorActions = selectedFile?.let {
            {
                TennyoIconButton(
                    icon = TennyoIcons.Undo,
                    contentDescription = stringResource(Res.string.local_undo),
                    onClick = { onIntent(LocalWorkspaceIntent.UndoSelectedFile) },
                    enabled = selectedEditor?.canUndo == true,
                )
                TennyoIconButton(
                    icon = TennyoIcons.Redo,
                    contentDescription = stringResource(Res.string.local_redo),
                    onClick = { onIntent(LocalWorkspaceIntent.RedoSelectedFile) },
                    enabled = selectedEditor?.canRedo == true,
                )
                if (state.canRunPython && isPythonScript(selectedFile)) {
                    TennyoIconButton(
                        icon = TennyoIcons.PlayArrow,
                        contentDescription = stringResource(Res.string.local_run),
                        onClick = { onIntent(LocalWorkspaceIntent.RunSelectedFile) },
                        enabled = state.canRun,
                    )
                }
                Spacer(Modifier.weight(1f))
                TennyoSaveButton(
                    phase = when {
                        selectedEditor?.saving == true -> SavePhase.SAVING
                        selectedEditor?.isDirty == true -> SavePhase.UNSAVED
                        else -> SavePhase.SAVED
                    },
                    countdown = selectedEditor?.autosave,
                    onClick = { onIntent(LocalWorkspaceIntent.SaveSelectedFile) },
                    saveLabel = stringResource(Res.string.local_save),
                    savingLabel = stringResource(Res.string.local_saving),
                    savedLabel = stringResource(Res.string.local_saved_state),
                )
            }
        },
        modifier = modifier,
    ) {
        Crossfade(targetState = selectedFile, label = "Local editor") { file ->
            LocalEditor(file, file?.let(state.editors::get), onIntent, keyboardVisible)
        }
    }
}

@Composable
private fun LocalEditor(
    path: String?,
    editor: LocalEditorState?,
    onIntent: (LocalWorkspaceIntent) -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        path == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.local_editor_empty))
        }

        editor == null || editor.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.local_editor_loading, path.substringAfterLast('/')))
        }

        else -> Column(modifier.fillMaxSize()) {
            editor.fault?.let { fault ->
                Text(
                    text = fault.text.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.small),
                )
            }
            CodeEditor(
                value = editor.text,
                onValueChange = { onIntent(LocalWorkspaceIntent.ChangeText(path, it)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                fileId = path,
                language = detectEditorLanguage(path.substringAfterLast('/'), textSample = editor.text),
                keyboardVisible = keyboardVisible,
            )
        }
    }
}
