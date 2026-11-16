package app.tennyo.mobile.feature.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoGitFileRow
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.relativeTime
import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.design.theme.monospaceFontFamily
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_changed_files
import app.tennyo.mobile.feature.git.generated.resources.git_changed_files_truncated
import app.tennyo.mobile.feature.git.generated.resources.git_commit
import app.tennyo.mobile.feature.git.generated.resources.git_committer
import app.tennyo.mobile.feature.git.generated.resources.git_copy_commit_hash
import app.tennyo.mobile.feature.git.generated.resources.git_loading
import app.tennyo.mobile.feature.git.generated.resources.git_parent
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_title
import org.jetbrains.compose.resources.stringResource

/** One commit in the Git sheet: its message, who made it, the hashes it hangs on, and the files it changed. */
@Composable
internal fun GitCommitDetailScreen(
    response: GitCommitDetails?,
    failure: GitFault?,
    loading: Boolean,
    onIntent: (GitIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = Spacing.none,
) {
    val clipboard = LocalClipboardManager.current
    val commit = response?.commit?.takeUnless { loading || failure != null }
    Column(modifier = modifier) {
        GitSheetHeader(
            title = commit?.shortId ?: stringResource(Res.string.git_commit),
            titleFontFamily = commit?.let { monospaceFontFamily() },
            onBack = null,
            onClose = onClose,
            actions = {
                commit?.let {
                    TennyoIconButton(
                        icon = TennyoIcons.ContentCopy,
                        contentDescription = stringResource(Res.string.git_copy_commit_hash),
                        onClick = { clipboard.setText(AnnotatedString(it.id)) },
                        iconSize = ComponentSizes.icon,
                    )
                }
            },
        )
        when {
            loading -> TennyoWorkingIndicator(
                modifier = Modifier.fillMaxWidth().weight(1f).wrapContentSize(Alignment.Center),
                label = stringResource(Res.string.git_loading),
            )

            failure != null -> TennyoStatusBanner(
                title = stringResource(Res.string.git_unavailable_title),
                message = failure.displayMessage(),
                tone = TennyoStatusTone.Failed,
                modifier = Modifier.padding(Spacing.large),
            )

            response != null && commit != null -> GitCommitContent(
                detail = response,
                commit = commit,
                onIntent = onIntent,
                contentPadding = PaddingValues(
                    start = Spacing.large,
                    end = Spacing.large,
                    top = Spacing.small,
                    bottom = bottomInset + Spacing.large,
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            else -> Unit
        }
    }
}

@Composable
private fun GitCommitContent(
    detail: GitCommitDetails,
    commit: GitCommitDetail,
    onIntent: (GitIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item(key = "message") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    commit.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                commit.fullMessage.substringAfter(commit.subject, "").trim().takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "author") { GitCommitByline(commit, Modifier.padding(top = Spacing.large)) }
        item(key = "facts") { GitCommitFacts(commit, Modifier.padding(top = Spacing.large)) }
        item(key = "files-label") {
            GitListLabel(
                stringResource(Res.string.git_changed_files),
                count = detail.files.size,
                modifier = Modifier.padding(bottom = Spacing.extraSmall),
            )
        }
        items(detail.files, key = { it.id }) { file ->
            val path = file.afterPath ?: file.beforePath.orEmpty()
            val directory = path.substringBeforeLast('/', "")
            val renamedFrom = file.beforePath?.takeIf { file.afterPath != null && it != file.afterPath }
            TennyoGitFileRow(
                status = file.kind.shortLabel(),
                fileName = path.substringAfterLast('/'),
                changedLines = renamedFrom?.let { "$it → $path" } ?: directory,
                onClick = { onIntent(GitIntent.OpenCommitFileDiff(detail.repositoryId, commit.id, file.id)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (detail.filesTruncated) {
            item(key = "truncated") { GitEmptyState(stringResource(Res.string.git_changed_files_truncated)) }
        }
    }
}

/** Who wrote the commit and when, set as a byline rather than boxed: the message above is what the page is about. */
@Composable
private fun GitCommitByline(commit: GitCommitDetail, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Box(
            modifier = Modifier.size(ComponentSizes.avatar)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                commit.authorName.commitInitials(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                commit.authorName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(commit.authorEmail, commit.authoredAtEpochMillis.relativeTime()).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The hash, its parent and, when someone else committed it, the committer: a short definition list. */
@Composable
private fun GitCommitFacts(commit: GitCommitDetail, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GitCommitFact(stringResource(Res.string.git_commit), commit.id, monospace = true)
        commit.parentIds.firstOrNull()?.let { parent ->
            GitCommitFact(stringResource(Res.string.git_parent), parent.take(SHORT_COMMIT_ID_LENGTH), monospace = true)
        }
        if (commit.committerName != commit.authorName) {
            GitCommitFact(stringResource(Res.string.git_committer), commit.committerName, monospace = false)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun GitCommitFact(label: String, value: String, monospace: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(min = FACT_LABEL_WIDTH),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) monospaceFontFamily() else null,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private fun String.commitInitials(): String =
    split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.take(1) }.uppercase()

/** Minimum label width, so the values start on one line. */
private val FACT_LABEL_WIDTH = 72.dp

private const val SHORT_COMMIT_ID_LENGTH = 7

internal fun GitChangeKind.shortLabel(): String = when (this) {
    GitChangeKind.ADDED -> "A"
    GitChangeKind.MODIFIED -> "M"
    GitChangeKind.RENAMED -> "R"
    GitChangeKind.DELETED -> "D"
    GitChangeKind.MOVED -> "R"
    // Git's own letters: `?` for untracked, `U` for unmerged.
    GitChangeKind.UNTRACKED -> "?"
    GitChangeKind.CONFLICTED -> "U"
}

@Preview(name = "Commit detail", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitCommitDetailPreview() = TennyoTheme(colorScheme = AppColorScheme.Dynamic) {
    GitScreenPreview { _, _, onIntent, modifier ->
        GitCommitDetailScreen(
            response = GitCommitDetails(
                repositoryId = "repo",
                revision = 4,
                commit = GitCommitDetail(
                    id = "commit-1",
                    shortId = "a1b2c3d",
                    subject = "Add existence guard to delete()",
                    fullMessage = "Add existence guard to delete()\n\nAvoid deleting records that no longer exist.",
                    authorName = "Maya Chen",
                    authorEmail = "maya@example.com",
                    authoredAtEpochMillis = 0,
                    committerName = "Maya Chen",
                    committerEmail = "maya@example.com",
                    committedAtEpochMillis = 0,
                    parentIds = listOf("parent"),
                ),
                files = listOf(
                    GitCommitFile(
                        id = "service",
                        kind = GitChangeKind.MODIFIED,
                        beforePath = "src/UserService.kt",
                        afterPath = "src/UserService.kt",
                    ),
                    GitCommitFile(
                        id = "test",
                        kind = GitChangeKind.ADDED,
                        afterPath = "test/UserServiceTest.kt",
                    ),
                ),
            ),
            failure = null,
            loading = false,
            onIntent = onIntent,
            onClose = {},
            modifier = modifier,
        )
    }
}
