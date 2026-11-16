package app.tennyo.mobile.feature.git

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.UnavailablePane
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_commit_to_parent
import app.tennyo.mobile.feature.git.generated.resources.git_loading
import app.tennyo.mobile.feature.git.generated.resources.git_retry
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_body
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_title
import app.tennyo.mobile.feature.git.generated.resources.git_working_tree_to_head
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The Git pane of [component]: the selected section, and the commit, diff or prompt opened over it in a sheet. */
@Composable
fun GitPane(
    component: GitPaneComponent,
    panePadding: PanePadding,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    GitPane(state = state, onIntent = component::onIntent, panePadding = panePadding, modifier = modifier)
}

/** The Git destination on a host that does not serve Git: always offered, and saying why it is empty. */
@Composable
fun GitUnavailablePane(modifier: Modifier = Modifier) {
    UnavailablePane(
        icon = TennyoIcons.CallSplit,
        title = stringResource(Res.string.git_unavailable_title),
        reason = stringResource(Res.string.git_unavailable_body),
        modifier = modifier,
    )
}

@Composable
fun GitPane(
    state: GitState,
    onIntent: (GitIntent) -> Unit,
    panePadding: PanePadding,
    modifier: Modifier = Modifier,
    initialSection: GitSection? = null,
    /** Read once per recomposition so the branch bar's sync age moves with the clock, not with a pushed string. */
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    val git = state
    val repository = git.overview?.repositories?.firstOrNull { it.id == git.selectedRepositoryId }
        ?: git.overview?.repositories?.firstOrNull()
    GitSectionScreen(
        git = git,
        repository = repository,
        selectedSection = initialSection ?: git.selectedSection,
        onIntent = onIntent,
        panePadding = panePadding,
        nowEpochMillis = nowEpochMillis,
        modifier = modifier.fillMaxSize(),
    )
    val destination = when {
        git.selectedDiff != null -> GitPaneDestination.Diff(git.selectedDiff)
        git.selectedCommit != null -> GitPaneDestination.Commit(git.selectedCommit)
        else -> null
    }
    destination?.let { GitDestinationSheet(git, repository, it, onIntent) }
    // Before GitSheets: the checkout, new-branch and delete sheets it opens stack over the list.
    if (git.branchesOpen && repository != null) GitBranchesSheet(git, repository, onIntent)
    GitSheets(git = git, repository = repository, onIntent = onIntent)
}

/**
 * A commit, or a diff, over the section it was opened from. One sheet holds both, so a commit's file opens in place
 * and its back arrow — or system back — returns to the commit; swiping the sheet away closes both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitDestinationSheet(
    git: GitState,
    repository: GitRepository?,
    destination: GitPaneDestination,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onIntent(GitIntent.DismissDestination) }
    }
    val diffOverCommit = destination is GitPaneDestination.Diff && git.selectedCommit != null
    ModalBottomSheet(
        onDismissRequest = { onIntent(GitIntent.DismissDestination) },
        sheetState = sheetState,
        modifier = modifier.statusBarsPadding(),
        contentWindowInsets = { WindowInsets() },
    ) {
        GitSheetBackHandler(enabled = diffOverCommit) { onIntent(GitIntent.CloseDestination) }
        val bottomInset = safeDrawingBottomInset()
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it is GitPaneDestination.Diff },
            transitionSpec = {
                val forward = targetState is GitPaneDestination.Diff
                val slide = tween<IntOffset>(AnimationDurations.regularMillis)
                val enter = slideInHorizontally(slide) { if (forward) it / SLIDE_FRACTION else -it / SLIDE_FRACTION } +
                    fadeIn(tween(AnimationDurations.regularMillis))
                val exit = slideOutHorizontally(slide) { if (forward) -it / SLIDE_FRACTION else it / SLIDE_FRACTION } +
                    fadeOut(tween(AnimationDurations.quickMillis))
                enter togetherWith exit
            },
            label = "Git sheet",
        ) { target ->
            when (target) {
                is GitPaneDestination.Diff -> GitDiffDestination(
                    git = git,
                    repository = repository,
                    key = target.key,
                    onIntent = onIntent,
                    onBack = if (git.selectedCommit != null) {
                        { onIntent(GitIntent.CloseDestination) }
                    } else {
                        null
                    },
                    onClose = close,
                    bottomInset = bottomInset,
                    modifier = Modifier.fillMaxSize(),
                )

                is GitPaneDestination.Commit -> GitCommitDetailScreen(
                    response = git.commitDetails[target.key],
                    failure = git.requestFailures[GitRequestKey.CommitDetail(target.key)],
                    loading = GitRequestKey.CommitDetail(target.key) in git.loadingKeys,
                    onIntent = onIntent,
                    onClose = close,
                    bottomInset = bottomInset,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** How far a sheet page slides when a commit and one of its files trade places: a nudge, not a full push. */
private const val SLIDE_FRACTION = 5

/**
 * One diff, with previous and next walking the list it was opened from — the working changes or the commit's files —
 * and, for a working change the source can discard, the roll-back action.
 */
@Composable
private fun GitDiffDestination(
    git: GitState,
    repository: GitRepository?,
    key: GitDiffKey,
    onIntent: (GitIntent) -> Unit,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val changes = repository?.changeLists?.flatMap(GitChangeList::changes).orEmpty()
    val siblings = key.commitId?.let { commitId ->
        git.commitDetails[GitCommitKey(key.repositoryId, commitId)]?.files.orEmpty().map(GitCommitFile::id)
    } ?: changes.map(GitChange::id)
    val index = siblings.indexOf(key.changeId).coerceAtLeast(0)
    val open = { id: String ->
        onIntent(
            key.commitId?.let { GitIntent.OpenCommitFileDiff(key.repositoryId, it, id) }
                ?: GitIntent.OpenDiff(key.repositoryId, id),
        )
    }
    val editorPath = git.workingChangeEditorPath(key, repository)
    val rollback = changes.firstOrNull { key.commitId == null && it.id == key.changeId }
        ?.takeIf { GitAction.ROLLBACK in it.availableActions && !git.isMutationBlocked(key.repositoryId) }
    GitDiffScreen(
        response = git.diffs[key],
        presentation = git.diffPresentations[key],
        failure = git.requestFailures[GitRequestKey.Diff(key)],
        loading = GitRequestKey.Diff(key) in git.loadingKeys,
        onBack = onBack,
        onClose = onClose,
        changeIndex = index,
        changeCount = siblings.size,
        onPrevious = siblings.getOrNull(index - 1)?.let { id -> { open(id) } },
        onNext = siblings.getOrNull(index + 1)?.let { id -> { open(id) } },
        comparison = key.commitId?.let { stringResource(Res.string.git_commit_to_parent, it.take(SHORT_ID)) }
            ?: stringResource(Res.string.git_working_tree_to_head),
        onOpenInEditor = editorPath?.let { path -> { onIntent(GitIntent.OpenInEditor(path)) } },
        onRollback = rollback?.let { change -> { onIntent(GitIntent.RollbackChange(key.repositoryId, change.id)) } },
        bottomInset = bottomInset,
        modifier = modifier,
    )
}

/** A commit's file may differ from the working tree's, so only a working change opens in the editor. */
private fun GitState.workingChangeEditorPath(key: GitDiffKey, repository: GitRepository?): String? =
    diffs[key]?.afterPath
        ?.takeIf { opensFilesInEditor && key.commitId == null }
        ?.let { editorPath(repository?.projectPath, it) }

private const val SHORT_ID = 7

private sealed interface GitPaneDestination {
    data class Commit(val key: GitCommitKey) : GitPaneDestination

    data class Diff(val key: GitDiffKey) : GitPaneDestination
}

@Composable
private fun GitSectionScreen(
    git: GitState,
    repository: GitRepository?,
    selectedSection: GitSection,
    onIntent: (GitIntent) -> Unit,
    panePadding: PanePadding,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (git.overview != null && repository == null && !git.isOverviewPending) {
        GitRepositoryMissingState(modifier = modifier)
        return
    }
    val layoutDirection = LocalLayoutDirection.current
    val scaffoldPadding = panePadding.contentPadding
    val listStartPadding =
        scaffoldPadding.calculateStartPadding(layoutDirection) + panePadding.contentHorizontalPadding
    val listEndPadding = scaffoldPadding.calculateEndPadding(layoutDirection) + panePadding.contentHorizontalPadding
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = scaffoldPadding.calculateTopPadding() + Spacing.small),
    ) {
        if (git.overview != null && git.overview.repositories.size > 1) {
            GitRepositoryChooser(
                repositories = git.overview.repositories,
                selectedRepositoryId = repository?.id,
                enabled = git.mutatingRepositories.isEmpty(),
                onSelected = { onIntent(GitIntent.SelectRepository(it)) },
                modifier = Modifier.padding(start = listStartPadding, end = listEndPadding, bottom = Spacing.small),
            )
        }
        AnimatedVisibility(
            visible = git.requestFailures[GitRequestKey.Overview] != null,
            modifier = Modifier.padding(start = listStartPadding, end = listEndPadding),
            enter = fadeIn(tween(AnimationDurations.regularMillis))
                    + expandVertically(tween(AnimationDurations.regularMillis)),
            exit = fadeOut(tween(AnimationDurations.regularMillis))
                    + shrinkVertically(tween(AnimationDurations.regularMillis)),
        ) {
            TennyoStatusBanner(
                title = stringResource(Res.string.git_unavailable_title),
                message = git.requestFailures[GitRequestKey.Overview]?.displayMessage() ?: "",
                tone = TennyoStatusTone.Failed,
                actionLabel = stringResource(Res.string.git_retry),
                onAction = { onIntent(GitIntent.Refresh) },
                modifier = Modifier.padding(bottom = Spacing.small),
            )
        }
        if (git.isOverviewPending) {
            TennyoWorkingIndicator(
                label = stringResource(Res.string.git_loading),
                modifier = Modifier.fillMaxWidth().weight(1f).wrapContentSize(Alignment.Center),
            )
        }
        repository?.let { selectedRepository ->
            val refreshing = GitRequestKey.Overview in git.loadingKeys
            GitMutationOutcomeBanner(
                outcome = git.mutationOutcome[selectedRepository.id],
                onDismiss = { onIntent(GitIntent.DismissOutcome(selectedRepository.id)) },
                modifier = Modifier.padding(start = listStartPadding, end = listEndPadding),
            )
            GitBranchBar(
                repository = selectedRepository,
                syncAge = GitSyncAge.of(
                    receivedAtEpochMillis = git.overviewReceivedAtEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    refreshing = refreshing,
                ),
                // The bar is the way into the branch list; the transfer controls beside it stay their own targets.
                onBranchClick = { onIntent(GitIntent.OpenBranches(selectedRepository.id)) },
                onRefresh = { onIntent(GitIntent.Refresh) },
                refreshing = refreshing,
                onPush = { onIntent(GitIntent.OpenPushPreview(selectedRepository.id)) },
                pushEnabled = selectedRepository.hasPushableCommits &&
                        !git.isMutationBlocked(selectedRepository.id),
                // Fetch lives in the Project tab; the bar keeps the two transfers that move the branch.
                onPull = { onIntent(GitIntent.Pull(selectedRepository.id)) }.takeIf {
                    git.capabilities.pull && selectedRepository.upstreamBranch != null
                },
                pullEnabled = !git.isMutationBlocked(selectedRepository.id),
                modifier = Modifier.padding(start = listStartPadding, end = listEndPadding),
            )
            GitSectionTabs(
                sections = git.capabilities.sections(),
                selectedSection = selectedSection,
                changeCount = selectedRepository.changeLists.sumOf { it.changes.size },
                onSectionSelected = { section -> onIntent(GitIntent.SelectSection(section)) },
                modifier = Modifier.padding(
                    start = listStartPadding,
                    end = listEndPadding,
                    top = Spacing.medium,
                ),
            )
        }
        val sectionPadding = PaddingValues(
            start = listStartPadding,
            top = Spacing.small,
            end = listEndPadding,
            bottom = scaffoldPadding.calculateBottomPadding() + Spacing.small,
        )
        repository?.let { selectedRepository ->
            when (selectedSection) {
                GitSection.Changes -> GitChangesScreen(
                    git = git,
                    repository = selectedRepository,
                    onIntent = onIntent,
                    contentPadding = sectionPadding,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                GitSection.History -> GitHistoryScreen(
                    git = git,
                    repository = selectedRepository,
                    onIntent = onIntent,
                    contentPadding = sectionPadding,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                GitSection.Project -> GitProjectScreen(
                    git = git,
                    repository = selectedRepository,
                    onIntent = onIntent,
                    contentPadding = sectionPadding,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
