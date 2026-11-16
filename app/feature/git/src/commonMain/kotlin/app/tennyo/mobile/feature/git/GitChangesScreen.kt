package app.tennyo.mobile.feature.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoCompactCheckbox
import app.tennyo.mobile.design.TennyoFocusedTextField
import app.tennyo.mobile.design.TennyoGitFileRow
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressButton
import app.tennyo.mobile.design.layout.PlatformPopupMenu
import app.tennyo.mobile.design.layout.TennyoMenuItem
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_all
import app.tennyo.mobile.feature.git.generated.resources.git_changes
import app.tennyo.mobile.feature.git.generated.resources.git_commit_and_push
import app.tennyo.mobile.feature.git.generated.resources.git_commit_changes
import app.tennyo.mobile.feature.git.generated.resources.git_commit_message
import app.tennyo.mobile.feature.git.generated.resources.git_commit_message_required_hint
import app.tennyo.mobile.feature.git.generated.resources.git_commit_message_too_long_hint
import app.tennyo.mobile.feature.git.generated.resources.git_commit_ready_hint
import app.tennyo.mobile.feature.git.generated.resources.git_commit_refresh_required_hint
import app.tennyo.mobile.feature.git.generated.resources.git_commit_select_changes_hint
import app.tennyo.mobile.feature.git.generated.resources.git_commit_selection_too_large_hint
import app.tennyo.mobile.feature.git.generated.resources.git_more_change_actions
import app.tennyo.mobile.feature.git.generated.resources.git_no_local_changes
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_files
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GitChangesScreen(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val changes = repository.changeLists.flatMap(GitChangeList::changes)
    val draft = git.commitDrafts[repository.id]
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // The dock below already clears the system bar, so the list only needs a gap above it.
            contentPadding = if (draft == null) {
                contentPadding
            } else {
                PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    top = contentPadding.calculateTopPadding(),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    bottom = Spacing.medium,
                )
            },
            verticalArrangement = Arrangement.spacedBy(Borders.regular),
        ) {
            item {
                val allSelected = changes.isNotEmpty() && draft?.selectedChangeIds?.size == changes.size
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The design states the selection before the commit button does anything with it.
                    GitSelectionSummary(
                        changes = changes,
                        selectedChangeIds = draft?.selectedChangeIds.orEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    draft?.let {
                        TennyoCompactCheckbox(
                            checked = allSelected,
                            onCheckedChange = {
                                onIntent(GitIntent.SelectAllChanges(repository.id, !allSelected))
                            },
                        )
                        Text(
                            text = stringResource(Res.string.git_all),
                            modifier = Modifier.padding(start = Spacing.extraSmall),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (changes.isEmpty()) item { GitEmptyState(stringResource(Res.string.git_no_local_changes)) }
            repository.changeLists.forEach { changeList ->
                items(changeList.changes, key = GitChange::id) { change ->
                    val renamePath = if (
                        change.beforePath != null && change.afterPath != null && change.beforePath != change.afterPath
                    ) "${change.beforePath} → ${change.afterPath}" else null
                    TennyoGitFileRow(
                        status = change.kind.shortLabel(),
                        fileName = change.fileName,
                        changedLines = listOfNotNull(
                            renamePath,
                            change.parentPath,
                        ).joinToString(" · "),
                        addedLines = change.addedLines,
                        deletedLines = change.deletedLines,
                        onClick = { onIntent(GitIntent.OpenDiff(repository.id, change.id)) },
                        selection = draft?.let {
                            {
                                TennyoCompactCheckbox(
                                    checked = change.id in it.selectedChangeIds,
                                    onCheckedChange = {
                                        onIntent(GitIntent.ToggleChange(
                                                repository.id,
                                                change.id
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
        draft?.let {
            GitCommitComposer(
                git = git,
                repository = repository,
                onIntent = onIntent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Corners.panelTop)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    )
                    .padding(top = Spacing.large),
            )
        }
    }
}

/**
 * The dock under the change list: the message, what is about to happen, and the actions on the selection — commit
 * first, with commit-and-push and roll back one tap further, in a menu beside it.
 */
@Composable
private fun GitCommitComposer(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = git.commitDrafts.getValue(repository.id)
    val canCommit = git.canCommit(repository.id)
    val rollbackable = git.rollbackableSelection(repository.id)
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        TennyoFocusedTextField(
            label = stringResource(Res.string.git_commit_message),
            value = draft.message,
            onValueChange = { onIntent(GitIntent.ChangeCommitMessage(repository.id, it)) },
            singleLine = false,
            maxLines = COMMIT_MESSAGE_MAX_LINES,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(git.commitValidationHint(repository.id)),
            modifier = Modifier.padding(horizontal = Spacing.extraSmall),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TennyoProgressButton(
                onClick = { onIntent(GitIntent.Commit(repository.id)) },
                modifier = Modifier.weight(1f),
                enabled = canCommit,
                loading = git.isMutating(repository.id),
                style = TennyoButtonStyle.Primary,
                size = TennyoButtonSize.PrimaryAction,
            ) {
                Icon(TennyoIcons.Check, contentDescription = null, modifier = Modifier.size(ComponentSizes.icon))
                Spacer(Modifier.width(Spacing.small))
                val selectedCount = draft.selectedChangeIds.size
                Text(pluralStringResource(Res.plurals.git_commit_changes, selectedCount, selectedCount))
            }
            PlatformPopupMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                anchor = {
                    TennyoIconButton(
                        icon = TennyoIcons.MoreVert,
                        contentDescription = stringResource(Res.string.git_more_change_actions),
                        onClick = { menuOpen = true },
                        enabled = !git.isMutationBlocked(repository.id),
                        size = ComponentSizes.primaryActionHeight,
                        iconSize = ComponentSizes.icon,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                },
            ) {
                TennyoMenuItem(
                    label = stringResource(Res.string.git_commit_and_push),
                    icon = TennyoIcons.Upload,
                    onClick = {
                        menuOpen = false
                        onIntent(GitIntent.CommitAndPush(repository.id))
                    },
                    enabled = canCommit && GitAction.PUSH in repository.availableActions,
                )
                TennyoMenuItem(
                    label = pluralStringResource(Res.plurals.git_rollback_files, rollbackable.size, rollbackable.size),
                    icon = TennyoIcons.Undo,
                    onClick = {
                        menuOpen = false
                        onIntent(GitIntent.RollbackSelectedChanges(repository.id))
                    },
                    enabled = rollbackable.isNotEmpty(),
                    destructive = true,
                )
            }
        }
    }
}

/** Enough lines for a subject and a short body before the field scrolls, so the list above keeps its room. */
private const val COMMIT_MESSAGE_MAX_LINES = 4

private fun GitState.commitValidationHint(repositoryId: String): StringResource {
    val capability = overview?.repositories?.firstOrNull { it.id == repositoryId }?.commitCapability
    val draft = commitDrafts[repositoryId]
    return when {
        capability == null || draft == null -> Res.string.git_commit_refresh_required_hint
        draft.stale || draft.revision != overview.revision -> Res.string.git_commit_refresh_required_hint
        draft.selectedChangeIds.isEmpty() -> Res.string.git_commit_select_changes_hint
        draft.selectedChangeIds.size > capability.maxSelectedChangeCount ->
            Res.string.git_commit_selection_too_large_hint
        draft.message.isBlank() -> Res.string.git_commit_message_required_hint
        draft.message.length > capability.maxMessageLength -> Res.string.git_commit_message_too_long_hint
        else -> Res.string.git_commit_ready_hint
    }
}

@Preview(name = "Changes - commit disabled", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitChangesDisabledPreview() {
    GitChangesPreview()
}

@Preview(name = "Changes - commit enabled", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitChangesEnabledPreview() {
    GitChangesPreview { state ->
        state.copy(commitDrafts = state.commitDrafts.mapValues { (_, draft) ->
            draft.copy(message = "Describe change")
        })
    }
}

@Preview(name = "Changes - submitting", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitChangesSubmittingPreview() {
    GitChangesPreview { state ->
        state.copy(
            commitDrafts = state.commitDrafts.mapValues { (_, draft) ->
                draft.copy(message = "Describe change")
            },
            mutatingRepositories = setOf("repo"),
        )
    }
}

@Composable
private fun GitChangesPreview(transform: (GitState) -> GitState = { it }) {
    GitScreenPreview(transform) { git, repository, onIntent, modifier ->
        GitChangesScreen(
            git = git,
            repository = repository,
            onIntent = onIntent,
            contentPadding = gitPreviewContentPadding,
            modifier = modifier.fillMaxSize(),
        )
    }
}
