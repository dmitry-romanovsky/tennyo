package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoTag
import app.tennyo.mobile.design.relativeTime
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_history_byline
import app.tennyo.mobile.feature.git.generated.resources.git_history_empty
import app.tennyo.mobile.feature.git.generated.resources.git_history_loading
import app.tennyo.mobile.feature.git.generated.resources.git_history_refs_truncated
import app.tennyo.mobile.feature.git.generated.resources.git_load_older_commits
import app.tennyo.mobile.feature.git.generated.resources.git_retry
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GitHistoryScreen(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val history = git.histories[repository.id]
    val layoutDirection = LocalLayoutDirection.current
    val horizontalPaddingValues = remember {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection)
        )
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        val initialFailure = git.requestFailures[GitRequestKey.History(repository.id)]
        initialFailure?.let { failure ->
            item {
                GitHistoryFailure(modifier = Modifier.padding(horizontalPaddingValues), failure) {
                    onIntent(GitIntent.LoadHistory(repository.id))
                }
            }
        }
        if (history == null) {
            if (initialFailure == null) item { GitEmptyState(stringResource(Res.string.git_history_loading)) }
            return@LazyColumn
        }
        if (history.response.commits.isEmpty()) {
            item { GitEmptyState(stringResource(Res.string.git_history_empty)) }
        }
        items(history.response.commits, key = GitCommitSummary::id) { commit ->
            GitHistoryRow(
                commit = commit,
                onClick = { onIntent(GitIntent.OpenCommit(repository.id, commit.id)) },
                modifier = Modifier.padding(horizontalPaddingValues),
            )
        }
        if (history.response.refsTruncated) {
            item { GitEmptyState(stringResource(Res.string.git_history_refs_truncated)) }
        }
        history.nextCursor?.let { cursor ->
            git.requestFailures[GitRequestKey.HistoryNext(repository.id, cursor)]?.let { failure ->
                item {
                    GitHistoryFailure(modifier = Modifier.padding(horizontalPaddingValues), failure) {
                        onIntent(GitIntent.LoadNextHistoryPage(repository.id))
                    }
                }
            }
            item {
                TennyoButton(
                    label = stringResource(Res.string.git_load_older_commits),
                    onClick = { onIntent(GitIntent.LoadNextHistoryPage(repository.id)) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                    loading = history.loadingNextPage,
                    style = TennyoButtonStyle.Text,
                    size = TennyoButtonSize.Medium,
                )
            }
        }
    }
}

/** One commit: its subject, who and when, and its short id with the first ref pointing at it. */
@Composable
private fun GitHistoryRow(
    commit: GitCommitSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoListRow(
        title = commit.subject,
        subtitle = stringResource(
            Res.string.git_history_byline,
            commit.authorName,
            commit.authoredAtEpochMillis.relativeTime(),
        ),
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        leading = {
            Icon(
                imageVector = if (commit.parentIds.size > 1) TennyoIcons.CallSplit else TennyoIcons.Commit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ComponentSizes.icon),
            )
        },
        trailing = {
            commit.refs.firstOrNull()?.let { ref -> TennyoTag(ref.displayName) }
            TennyoTag(commit.shortId)
        },
    )
}

@Composable
private fun GitHistoryFailure(modifier: Modifier = Modifier, failure: GitFault, onRetry: () -> Unit) {
    TennyoStatusBanner(
        title = stringResource(Res.string.git_unavailable_title),
        message = failure.displayMessage(),
        modifier = modifier,
        tone = TennyoStatusTone.Failed,
        actionLabel = stringResource(Res.string.git_retry),
        onAction = onRetry,
    )
}

@Preview(name = "History - loaded", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitHistoryLoadedPreview() {
    GitHistoryPreviewSurface()
}

@Preview(name = "History - loading", widthDp = 420, heightDp = 300, showBackground = true)
@Composable
private fun GitHistoryLoadingPreview() {
    GitHistoryPreviewSurface {
        it.copy(
            histories = emptyMap(),
            loadingKeys = setOf(GitRequestKey.History("repo")),
        )
    }
}

@Preview(name = "History - empty", widthDp = 420, heightDp = 300, showBackground = true)
@Composable
private fun GitHistoryEmptyPreview() {
    GitHistoryPreviewSurface { state ->
        state.copy(
            histories = mapOf(
                "repo" to GitHistoryState(
                    GitHistoryPage("repo", 4, emptyList()),
                ),
            ),
        )
    }
}

@Preview(name = "History - error", widthDp = 420, heightDp = 300, showBackground = true)
@Composable
private fun GitHistoryErrorPreview() {
    GitHistoryPreviewSurface {
        it.copy(
            histories = emptyMap(),
            requestFailures = mapOf(
                GitRequestKey.History("repo") to GitFault.Rejected(GitRejection.PLATFORM_FAILURE),
            ),
        )
    }
}

@Composable
private fun GitHistoryPreviewSurface(transform: (GitState) -> GitState = { it }) {
    GitScreenPreview(transform) { git, repository, onIntent, modifier ->
        GitHistoryScreen(
            git = git,
            repository = repository,
            onIntent = onIntent,
            contentPadding = gitPreviewContentPadding,
            modifier = modifier,
        )
    }
}
