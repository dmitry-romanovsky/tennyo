package app.tennyo.mobile.feature.git

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoSearchField
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoTag
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_ahead
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_behind
import app.tennyo.mobile.feature.git.generated.resources.git_branches
import app.tennyo.mobile.feature.git.generated.resources.git_branches_loading
import app.tennyo.mobile.feature.git.generated.resources.git_current_branch
import app.tennyo.mobile.feature.git.generated.resources.git_delete_branch
import app.tennyo.mobile.feature.git.generated.resources.git_local_branches
import app.tennyo.mobile.feature.git.generated.resources.git_local_branches_truncated
import app.tennyo.mobile.feature.git.generated.resources.git_new_branch
import app.tennyo.mobile.feature.git.generated.resources.git_remote_branches_label
import app.tennyo.mobile.feature.git.generated.resources.git_remote_branches_truncated
import app.tennyo.mobile.feature.git.generated.resources.git_search_branches
import app.tennyo.mobile.feature.git.generated.resources.git_unavailable_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The branch list, opened from the branch bar: search, then local and remote branches as folders by their `/`
 * segments. Tapping a branch asks to check it out; a local branch the source can delete has its own delete button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitBranchesSheet(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onIntent(GitIntent.DismissBranches) }
    }
    ModalBottomSheet(
        onDismissRequest = { onIntent(GitIntent.DismissBranches) },
        sheetState = sheetState,
        modifier = modifier.statusBarsPadding(),
        contentWindowInsets = { WindowInsets() },
    ) {
        // A fixed height, so the sheet does not jump while the search narrows the list.
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(BRANCH_SHEET_HEIGHT_FRACTION)) {
            GitSheetHeader(
                title = stringResource(Res.string.git_branches),
                subtitle = repository.rootName,
                onBack = null,
                onClose = close,
                actions = {
                    if (git.capabilities.createBranch) {
                        TennyoIconButton(
                            icon = TennyoIcons.Add,
                            contentDescription = stringResource(Res.string.git_new_branch),
                            onClick = { onIntent(GitIntent.OpenNewBranch(repository.id)) },
                            enabled = !git.isMutationBlocked(repository.id),
                            iconSize = ComponentSizes.icon,
                        )
                    }
                },
            )
            TennyoSearchField(
                value = git.branchSearchQuery,
                onValueChange = { onIntent(GitIntent.ChangeBranchSearch(it)) },
                placeholder = stringResource(Res.string.git_search_branches),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
            GitBranchList(
                git = git,
                repository = repository,
                onIntent = onIntent,
                contentPadding = PaddingValues(
                    start = Spacing.small,
                    end = Spacing.small,
                    bottom = safeDrawingBottomInset() + Spacing.medium,
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private const val BRANCH_SHEET_HEIGHT_FRACTION = 0.92f

@Composable
private fun GitBranchList(
    git: GitState,
    repository: GitRepository,
    onIntent: (GitIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val branches = git.branches[repository.id]
    val failure = git.requestFailures[GitRequestKey.Branches(repository.id)]
    val blocked = git.isMutationBlocked(repository.id)
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        failure?.let {
            item(key = "failure") {
                TennyoStatusBanner(
                    title = stringResource(Res.string.git_unavailable_title),
                    message = it.displayMessage(),
                    tone = TennyoStatusTone.Failed,
                    modifier = Modifier.padding(horizontal = Spacing.small),
                )
            }
        }
        if (branches == null) {
            if (failure == null) {
                item(key = "loading") { GitEmptyState(stringResource(Res.string.git_branches_loading)) }
            }
            return@LazyColumn
        }
        val rowFor: @Composable (GitBranch, String) -> Unit = { branch, displayName ->
            GitBranchRow(
                branch = branch,
                displayName = displayName,
                enabled = !blocked,
                // A source that can create a tracking branch offers checkout of a remote branch too.
                onCheckout = if (!branch.isCurrent && GitAction.CHECKOUT in branch.availableActions) {
                    {
                        onIntent(
                            GitIntent.OpenBranchAction(repository.id, branch.id, branch.displayName, branches.revision),
                        )
                    }
                } else {
                    null
                },
                onDelete = if (GitAction.DELETE in branch.availableActions) {
                    {
                        onIntent(
                            GitIntent.DeleteBranch(repository.id, branch.id, branch.displayName, branches.revision),
                        )
                    }
                } else {
                    null
                },
            )
        }
        branchSection(
            label = Res.string.git_local_branches,
            branches = filterRemoteGitBranches(branches.localBranches, git.branchSearchQuery),
            truncated = Res.string.git_local_branches_truncated.takeIf { branches.localBranchesTruncated },
            scope = "local",
            git = git,
            onIntent = onIntent,
            row = rowFor,
        )
        branchSection(
            label = Res.string.git_remote_branches_label,
            branches = filterRemoteGitBranches(branches.remoteBranches, git.branchSearchQuery),
            truncated = Res.string.git_remote_branches_truncated.takeIf { branches.remoteBranchesTruncated },
            scope = "remote",
            git = git,
            onIntent = onIntent,
            row = rowFor,
        )
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.branchSection(
    label: StringResource,
    branches: List<GitBranch>,
    truncated: StringResource?,
    scope: String,
    git: GitState,
    onIntent: (GitIntent) -> Unit,
    row: @Composable (GitBranch, String) -> Unit,
) {
    if (branches.isNotEmpty()) {
        item(key = "$scope-label") {
            GitListLabel(
                label = stringResource(label),
                count = branches.size,
                modifier = Modifier
                    .padding(start = Spacing.small, bottom = Spacing.extraSmall)
                    .animateItem(placementSpec = tween(AnimationDurations.regularMillis)),
            )
        }
        branchTree(
            branches = branches,
            scope = scope,
            expandedFolders = git.expandedBranchFolders,
            expandAll = git.branchSearchQuery.isNotBlank(),
            onToggleFolder = { onIntent(GitIntent.ToggleBranchFolder(it)) },
            content = row,
        )
    }
    truncated?.let { item(key = "$scope-truncated") { GitEmptyState(stringResource(it)) } }
}

private fun LazyListScope.branchTree(
    branches: List<GitBranch>,
    scope: String,
    expandedFolders: Set<String>,
    expandAll: Boolean,
    onToggleFolder: (String) -> Unit,
    content: @Composable (GitBranch, String) -> Unit,
) {
    val visibleNodes =
        visibleRemoteGitBranchNodes(buildRemoteGitBranchTree(branches, scope), expandedFolders, expandAll)
    items(visibleNodes, key = { it.node.id }) { visible ->
        val itemModifier = Modifier
            .animateItem(
                fadeInSpec = tween(AnimationDurations.quickMillis),
                placementSpec = tween(AnimationDurations.regularMillis),
                fadeOutSpec = tween(AnimationDurations.quickMillis),
            )
            .padding(start = Spacing.large * visible.depth)
        when (val node = visible.node) {
            is GitBranchNode.Folder -> GitBranchFolderRow(
                node = node,
                expanded = expandAll || node.id in expandedFolders,
                onToggle = { onToggleFolder(node.id) },
                modifier = itemModifier,
            )

            is GitBranchNode.Leaf -> Box(modifier = itemModifier) { content(node.branch, node.name) }
        }
    }
}

/** A `/` segment shared by several branches: one chevron that turns, the name, and how many it holds. */
@Composable
private fun GitBranchFolderRow(
    node: GitBranchNode.Folder,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "Branch folder disclosure",
    )
    GitBranchRowFrame(onClick = onToggle, enabled = true, modifier = modifier) {
        Icon(
            imageVector = TennyoIcons.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(ComponentSizes.icon).graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = node.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = node.children.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One branch: the current one marked in the primary colour with a tag, the others with the branch glyph. Under the
 * name, what it tracks and how far apart they are, in the branch bar's colours.
 */
@Composable
private fun GitBranchRow(
    branch: GitBranch,
    displayName: String,
    enabled: Boolean,
    onCheckout: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    GitBranchRowFrame(
        onClick = onCheckout,
        enabled = enabled,
        modifier = modifier,
        highlighted = branch.isCurrent,
    ) {
        Box(
            modifier = Modifier
                .size(ComponentSizes.icon)
                .clip(Corners.full)
                .background(if (branch.isCurrent) colors.primary else colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (branch.isCurrent) TennyoIcons.Check else TennyoIcons.CallSplit,
                contentDescription = null,
                modifier = Modifier.size(ComponentSizes.compactIcon),
                tint = if (branch.isCurrent) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.small)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (branch.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (branch.isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            GitBranchSyncLine(branch)
        }
        if (branch.isCurrent) TennyoTag(stringResource(Res.string.git_current_branch))
        onDelete?.let {
            TennyoIconButton(
                icon = TennyoIcons.Delete,
                contentDescription = stringResource(Res.string.git_delete_branch),
                onClick = it,
                enabled = enabled,
                contentColor = colors.onSurfaceVariant,
            )
        }
    }
}

/** "origin/main · 2 ahead · 1 behind"; nothing when the branch tracks nothing. */
@Composable
private fun GitBranchSyncLine(branch: GitBranch, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val parts = listOfNotNull(
        branch.trackedUpstream?.let { it to colors.onSurfaceVariant },
        branch.aheadCount?.takeIf { it > 0 }
            ?.let { stringResource(Res.string.git_branch_bar_ahead, it) to colors.primary },
        branch.behindCount?.takeIf { it > 0 }
            ?.let { stringResource(Res.string.git_branch_bar_behind, it) to colors.onSurface },
    )
    if (parts.isEmpty()) return
    Text(
        text = buildAnnotatedString {
            parts.forEachIndexed { index, (part, color) ->
                if (index > 0) append(" · ")
                withStyle(SpanStyle(color = color)) { append(part) }
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GitBranchRowFrame(
    onClick: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ComponentSizes.iconButton + Spacing.small)
            .clip(Corners.sm)
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = Spacing.small, end = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        content = content,
    )
}

@Preview(name = "Branches sheet", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitBranchesPreview() {
    GitBranchesPreviewSurface()
}

@Preview(name = "Branches sheet - recursive search", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitBranchSearchPreview() {
    GitBranchesPreviewSurface { it.copy(branchSearchQuery = "pagination") }
}

@Composable
private fun GitBranchesPreviewSurface(transform: (GitState) -> GitState = { it }) {
    GitScreenPreview(transform) { git, repository, onIntent, modifier ->
        GitBranchList(
            git = git,
            repository = repository,
            onIntent = onIntent,
            contentPadding = PaddingValues(Spacing.small),
            modifier = modifier,
        )
    }
}
