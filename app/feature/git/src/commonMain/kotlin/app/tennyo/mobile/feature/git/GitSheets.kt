package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoCodeSurface
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressButton
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_branch_actions
import app.tennyo.mobile.feature.git.generated.resources.git_cancel
import app.tennyo.mobile.feature.git.generated.resources.git_checkout
import app.tennyo.mobile.feature.git.generated.resources.git_checkout_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_checkout_question
import app.tennyo.mobile.feature.git.generated.resources.git_commits_ahead
import app.tennyo.mobile.feature.git.generated.resources.git_loading
import app.tennyo.mobile.feature.git.generated.resources.git_outgoing_commits_truncated
import app.tennyo.mobile.feature.git.generated.resources.git_push_commits
import app.tennyo.mobile.feature.git.generated.resources.git_push_review
import app.tennyo.mobile.feature.git.generated.resources.git_retry
import app.tennyo.mobile.feature.git.generated.resources.git_target
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_title
import app.tennyo.mobile.feature.git.generated.resources.git_up_to_date
import org.jetbrains.compose.resources.stringResource
@Composable
internal fun GitSheets(
    git: GitState,
    repository: GitRepository?,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    git.prompt?.let { prompt ->
        GitPromptSheet(prompt, onIntent, modifier)
        return
    }
    git.checkoutSelection?.takeIf { it.repositoryId == repository?.id }?.let { selection ->
        val loading = git.isMutating(selection.repositoryId)
        GitModalBottomSheet(
            onDismissRequest = { if (!loading) onIntent(GitIntent.DismissBranchAction) },
            modifier = modifier,
        ) {
            TennyoBottomSheetHeader(
                title = stringResource(Res.string.git_branch_actions),
                actionLabel = stringResource(Res.string.git_cancel),
                onAction = {
                    if (!loading) onIntent(GitIntent.DismissBranchAction)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            GitCheckoutConfirmation(
                selection,
                git.isMutationBlocked(selection.repositoryId),
                loading,
                onIntent,
                Modifier.padding(Spacing.medium)
            )
            Spacer(Modifier.height(safeDrawingBottomInset()))
        }
    }
    repository?.let { selectedRepository ->
        val requestKey = GitRequestKey.PushPreview(selectedRepository.id)
        val preview = git.pushPreviews[selectedRepository.id]
        val requestLoading = requestKey in git.loadingKeys
        val failure = git.requestFailures[requestKey]
        if (preview == null && !requestLoading && failure == null) return@let
        val mutationLoading = git.isMutating(selectedRepository.id)
        GitModalBottomSheet(
            onDismissRequest = { if (!mutationLoading && !requestLoading) onIntent(GitIntent.DismissPushPreview) },
            modifier = modifier,
        ) {
            TennyoBottomSheetHeader(
                title = stringResource(Res.string.git_push_review),
                actionLabel = stringResource(Res.string.git_cancel),
                onAction = {
                    if (!mutationLoading && !requestLoading) {
                        onIntent(GitIntent.DismissPushPreview)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                requestLoading -> TennyoWorkingIndicator(
                    modifier = Modifier.padding(Spacing.medium),
                    label = stringResource(Res.string.git_loading),
                )

                failure != null -> TennyoStatusBanner(
                    title = stringResource(Res.string.git_unavailable_title),
                    message = failure.displayMessage(),
                    tone = TennyoStatusTone.Failed,
                    actionLabel = stringResource(Res.string.git_retry),
                    onAction = { onIntent(GitIntent.OpenPushPreview(selectedRepository.id)) },
                    modifier = Modifier.padding(Spacing.medium),
                )

                preview != null -> GitPushPreview(
                    preview,
                    git.isMutationBlocked(preview.repositoryId),
                    mutationLoading,
                    onIntent,
                    Modifier.padding(Spacing.medium)
                )
            }
            Spacer(Modifier.height(safeDrawingBottomInset()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        contentWindowInsets = { WindowInsets() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun GitCheckoutConfirmation(
    selection: GitCheckoutSelection,
    blocked: Boolean,
    loading: Boolean,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoCodeSurface(modifier) {
        Text(
            text = stringResource(Res.string.git_checkout_question, selection.branchName),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(Res.string.git_checkout_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.small))
        TennyoProgressButton(
            { onIntent(GitIntent.ConfirmCheckout) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !blocked,
            loading = loading,
            style = TennyoButtonStyle.Primary,
            size = TennyoButtonSize.PrimaryAction,
        ) { Text(stringResource(Res.string.git_checkout)) }
    }
}

@Composable
private fun GitPushPreview(
    preview: GitPushPreview,
    blocked: Boolean,
    loading: Boolean,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        GitListLabel(stringResource(Res.string.git_target))
        Surface(shape = Corners.sm, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Icon(
                    TennyoIcons.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(preview.upstreamBranch, style = MaterialTheme.typography.labelLarge)
                    Text(
                        preview.remoteDisplayUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        GitListLabel(stringResource(Res.string.git_commits_ahead, preview.commits.size))
        preview.commits.forEach { commit ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Icon(
                    TennyoIcons.Commit,
                    contentDescription = null,
                    modifier = Modifier.size(ComponentSizes.compactIcon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(commit.subject, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        commit.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                app.tennyo.mobile.design.TennyoTag(commit.shortId)
            }
        }
        if (preview.commitsTruncated) Text(
            stringResource(Res.string.git_outgoing_commits_truncated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (preview.commits.isEmpty()) Text(
            stringResource(Res.string.git_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TennyoProgressButton(
            onClick = {
                onIntent(GitIntent.Push(
                        preview.repositoryId,
                        preview.pushTargetId,
                        preview.revision
                    )
                )
            },
            enabled = !blocked && preview.commits.isNotEmpty(),
            loading = loading,
            modifier = Modifier.fillMaxWidth(),
            style = TennyoButtonStyle.Primary,
            size = TennyoButtonSize.PrimaryAction,
        ) {
            Icon(TennyoIcons.Upload, contentDescription = null)
            Text(stringResource(Res.string.git_push_commits, preview.commits.size))
        }
    }
}

@Preview(name = "Checkout sheet", widthDp = 420, heightDp = 400, showBackground = true)
@Composable
private fun GitCheckoutSheetPreview() {
    GitScreenPreview(
        transform = {
            it.copy(checkoutSelection = GitCheckoutSelection("repo", "develop", "develop", 4))
        },
    ) { git, repository, onIntent, modifier ->
        GitSheets(git, repository, onIntent, modifier)
    }
}

@Preview(name = "Push sheet - up to date", widthDp = 420, heightDp = 400, showBackground = true)
@Composable
private fun GitPushUpToDateSheetPreview() {
    GitPushSheetPreviewSurface(upToDate = true)
}

@Preview(name = "Push sheet", widthDp = 420, heightDp = 600, showBackground = true)
@Composable
private fun GitPushSheetPreview() {
    GitPushSheetPreviewSurface(upToDate = false)
}

@Composable
private fun GitPushSheetPreviewSurface(upToDate: Boolean) {
    GitScreenPreview(
        transform = {
            val state = previewGitState(showPushReview = true)
            if (upToDate) {
                state.copy(
                    pushPreviews = state.pushPreviews.mapValues { (_, preview) ->
                        preview.copy(commits = emptyList())
                    },
                )
            } else {
                state
            }
        },
    ) { git, repository, onIntent, modifier ->
        GitSheets(git, repository, onIntent, modifier)
    }
}
