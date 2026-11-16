package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_fetch
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_apply_stash
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_apply_stash_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_clean_up
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_clean_up_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_fetch_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_pull_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_push_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_reset_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_stash
import app.tennyo.mobile.feature.git.generated.resources.git_project_action_stash_body
import app.tennyo.mobile.feature.git.generated.resources.git_project_actions
import app.tennyo.mobile.feature.git.generated.resources.git_project_add_remote
import app.tennyo.mobile.feature.git.generated.resources.git_project_branch
import app.tennyo.mobile.feature.git.generated.resources.git_project_detached
import app.tennyo.mobile.feature.git.generated.resources.git_project_details
import app.tennyo.mobile.feature.git.generated.resources.git_project_head
import app.tennyo.mobile.feature.git.generated.resources.git_project_identity
import app.tennyo.mobile.feature.git.generated.resources.git_project_identity_missing
import app.tennyo.mobile.feature.git.generated.resources.git_project_identity_unset
import app.tennyo.mobile.feature.git.generated.resources.git_project_loading
import app.tennyo.mobile.feature.git.generated.resources.git_project_location
import app.tennyo.mobile.feature.git.generated.resources.git_project_no_commits
import app.tennyo.mobile.feature.git.generated.resources.git_project_no_remotes
import app.tennyo.mobile.feature.git.generated.resources.git_project_remotes
import app.tennyo.mobile.feature.git.generated.resources.git_project_stashes
import app.tennyo.mobile.feature.git.generated.resources.git_pull
import app.tennyo.mobile.feature.git.generated.resources.git_push
import app.tennyo.mobile.feature.git.generated.resources.git_reset_head
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Project tab: what the repository is (location, branch, last commit, author, stashes), its remotes — each opens
 * the remote editor — and the maintenance actions the source offers. Everything acts through [onIntent].
 */
@Composable
internal fun GitProjectScreen(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val info = git.projectInfos[repository.id]
    val busy = git.isMutationBlocked(repository.id)
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        if (info == null) {
            item { GitEmptyState(stringResource(Res.string.git_project_loading)) }
            return@LazyColumn
        }
        details(info, git.capabilities.identity, repository.id, onIntent)
        remotes(info, repository.id, onIntent)
        actions(git, repository, busy, onIntent)
    }
}

private fun LazyListScope.details(
    info: GitProjectInfo,
    identityAsked: Boolean,
    repositoryId: String,
    onIntent: (GitIntent) -> Unit,
) {
    item(key = "details") { GitListLabel(stringResource(Res.string.git_project_details)) }
    item(key = "location") { InfoRow(TennyoIcons.Folder, Res.string.git_project_location, info.location) }
    item(key = "branch") {
        InfoRow(
            TennyoIcons.CallSplit,
            Res.string.git_project_branch,
            info.branch ?: stringResource(Res.string.git_project_detached),
        )
    }
    item(key = "head") {
        InfoRow(
            TennyoIcons.Commit,
            Res.string.git_project_head,
            info.head?.let { "${it.shortId} · ${it.subject}" } ?: stringResource(Res.string.git_project_no_commits),
        )
    }
    item(key = "identity") {
        // Where the source keeps the author itself, it can be changed at any time, not only when a commit asks.
        InfoRow(
            TennyoIcons.Edit,
            Res.string.git_project_identity,
            info.identity?.let { "${it.name} <${it.email}>" } ?: stringResource(
                if (identityAsked) Res.string.git_project_identity_missing else Res.string.git_project_identity_unset,
            ),
            onClick = if (identityAsked) {
                { onIntent(GitIntent.EditIdentity(repositoryId)) }
            } else {
                null
            },
        )
    }
    item(key = "stashes") {
        InfoRow(TennyoIcons.Save, Res.string.git_project_stashes, info.stashCount.toString())
    }
}

private fun LazyListScope.remotes(info: GitProjectInfo, repositoryId: String, onIntent: (GitIntent) -> Unit) {
    item(key = "remotes") { GitListLabel(stringResource(Res.string.git_project_remotes)) }
    if (info.remotes.isEmpty()) {
        item(key = "no-remotes") { GitEmptyState(stringResource(Res.string.git_project_no_remotes)) }
    }
    items(info.remotes, key = { "remote:${it.name}" }) { remote ->
        TennyoListRow(
            title = remote.name,
            subtitle = remote.url,
            onClick = { onIntent(GitIntent.OpenRemote(repositoryId, remote)) },
            modifier = Modifier.fillMaxWidth(),
            leading = { RowIcon(TennyoIcons.Link) },
            trailing = { RowIcon(TennyoIcons.Edit) },
        )
    }
    item(key = "add-remote") {
        TennyoButton(
            label = stringResource(Res.string.git_project_add_remote),
            onClick = { onIntent(GitIntent.OpenRemote(repositoryId, remote = null)) },
            style = TennyoButtonStyle.Text,
            size = TennyoButtonSize.Medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LazyListScope.actions(
    git: GitState,
    repository: GitRepository,
    busy: Boolean,
    onIntent: (GitIntent) -> Unit,
) {
    val id = repository.id
    val hasRemote = git.projectInfos[id]?.remotes?.isNotEmpty() == true
    val hasStash = (git.projectInfos[id]?.stashCount ?: 0) > 0
    // Stash and reset act on tracked files only, so untracked ones alone leave nothing for them to do.
    val hasTrackedChanges = repository.changeLists.flatMap { it.changes }.any { it.kind != GitChangeKind.UNTRACKED }
    val project = { action: GitProjectAction -> GitIntent.RunProjectAction(id, action) }
    val strings = Res.string
    val rows = listOfNotNull(
        ActionRow({ TennyoIcons.Download }, strings.git_fetch, strings.git_project_action_fetch_body, hasRemote) {
            GitIntent.Fetch(id)
        }.takeIf { git.capabilities.fetch },
        ActionRow(
            { TennyoIcons.ArrowDownward },
            strings.git_pull,
            strings.git_project_action_pull_body,
            repository.upstreamBranch != null,
        ) { GitIntent.Pull(id) }.takeIf { git.capabilities.pull },
        ActionRow(
            { TennyoIcons.ArrowUpward },
            strings.git_push,
            strings.git_project_action_push_body,
            repository.hasPushableCommits,
        ) { GitIntent.OpenPushPreview(id) },
        ActionRow(
            { TennyoIcons.Save },
            strings.git_project_action_stash,
            strings.git_project_action_stash_body,
            hasTrackedChanges,
        ) { project(GitProjectAction.STASH) },
        ActionRow(
            { TennyoIcons.Redo },
            strings.git_project_action_apply_stash,
            strings.git_project_action_apply_stash_body,
            hasStash,
        ) { project(GitProjectAction.APPLY_STASH) },
        ActionRow(
            { TennyoIcons.Undo },
            strings.git_reset_head,
            strings.git_project_action_reset_body,
            hasTrackedChanges,
        ) { project(GitProjectAction.RESET_HEAD) },
        ActionRow(
            { TennyoIcons.Settings },
            strings.git_project_action_clean_up,
            strings.git_project_action_clean_up_body,
        ) { project(GitProjectAction.CLEAN_UP) },
    )
    item(key = "actions") { GitListLabel(stringResource(Res.string.git_project_actions)) }
    items(rows, key = { "action:${it.title.key}" }) { row ->
        TennyoListRow(
            title = stringResource(row.title),
            subtitle = stringResource(row.body),
            onClick = { onIntent(row.intent()) },
            enabled = !busy && row.enabled,
            modifier = Modifier.fillMaxWidth(),
            leading = { RowIcon(row.icon()) },
        )
    }
}

private class ActionRow(
    val icon: @Composable () -> ImageVector,
    val title: StringResource,
    val body: StringResource,
    val enabled: Boolean = true,
    val intent: () -> GitIntent,
)

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: StringResource,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    TennyoListRow(
        title = value,
        subtitle = stringResource(label),
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        leading = { RowIcon(icon) },
        trailing = onClick?.let { { RowIcon(TennyoIcons.ChevronRight) } },
    )
}

@Composable
private fun RowIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(ComponentSizes.icon),
    )
}

@Preview(showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun GitProjectScreenPreview() {
    GitScreenPreview(
        transform = { git ->
            val id = requireNotNull(git.selectedRepositoryId)
            git.copy(
                capabilities = GitSourceCapabilities(fetch = true, pull = true, projectSettings = true),
                projectInfos = mapOf(
                    id to GitProjectInfo(
                        location = "demo",
                        branch = "main",
                        head = null,
                        identity = GitIdentity("Ada", "ada@example.com"),
                        remotes = listOf(GitRemote("origin", "https://github.com/example/demo.git")),
                        stashCount = 1,
                        tagCount = 0,
                    ),
                ),
            )
        },
    ) { git, repository, onIntent, modifier ->
        GitProjectScreen(git, repository, onIntent, gitPreviewContentPadding, modifier)
    }
}
