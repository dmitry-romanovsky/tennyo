// Sample repository data for previews; its line counts and limits are illustrative.
@file:Suppress("MagicNumber")

package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.TennyoTheme

@Preview(name = "Git panel", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitPanePreview() {
    GitPaneSamplePreview()
}

@Preview(name = "Git panel refreshing", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitPaneRefreshingPreview() {
    GitPaneSamplePreview { it.copy(loadingKeys = setOf(GitRequestKey.Overview)) }
}

@Composable
private fun GitPaneSamplePreview(
    transform: (GitState) -> GitState = { it },
) {
    TennyoTheme(themeMode = AppThemeMode.Auto) {
        GitPane(
            state = remember { transform(previewGitState()) },
            onIntent = {},
            panePadding = panePadding(
                PaddingValues(),
                LayoutDirection.Ltr,
                0.dp,
            ),
            modifier = Modifier.fillMaxSize(),
            initialSection = GitSection.Changes,
        )
    }
}

internal fun previewGitState(
    binaryDiff: Boolean = false,
    unavailableDiff: Boolean = false,
    showPushReview: Boolean = false,
): GitState {
    val repository = previewRepository()
    val selectedDiff = GitDiffKey(repository.id, "service")
    val diff = previewDiff(repository.id, selectedDiff.changeId, binaryDiff, unavailableDiff)
    return GitState(
        overview = GitStatus(listOf(repository), selectedRepositoryId = repository.id),
        selectedRepositoryId = repository.id,
        commitDrafts = mapOf(
            repository.id to GitCommitDraft(
                selectedChangeIds = repository.changeLists.flatMap(GitChangeList::changes)
                    .mapTo(linkedSetOf(), GitChange::id),
                revision = PREVIEW_REVISION,
            ),
        ),
        selectedDiff = selectedDiff.takeIf { binaryDiff || unavailableDiff },
        diffs = mapOf(selectedDiff to diff),
        diffPresentations = mapOf(selectedDiff to diff.toPresentation()),
        histories = mapOf(
            repository.id to GitHistoryState(
                GitHistoryPage(repository.id, PREVIEW_REVISION, previewCommits(), nextCursor = "next-page"),
            ),
        ),
        branches = mapOf(repository.id to previewBranches(repository.id)),
        pushPreviews = if (showPushReview) mapOf(repository.id to previewPush(repository.id)) else emptyMap(),
    )
}

private fun previewRepository() = GitRepository(
    id = "repo",
    rootName = "demo",
    currentBranch = "main",
    upstreamBranch = "origin/main",
    aheadCount = 2,
    behindCount = 0,
    availableActions = setOf(GitAction.REFRESH, GitAction.VIEW_HISTORY, GitAction.VIEW_BRANCHES, GitAction.PUSH),
    commitCapability = GitCommitLimits(GIT_PREVIEW_MAX_CHANGES, GIT_PREVIEW_MAX_MESSAGE_LENGTH),
    changeLists = listOf(
        GitChangeList(
            "default",
            "Default",
            listOf(
                previewChange("service", GitChangeKind.MODIFIED, "src/main/kotlin/service/UserService.kt", 24, 3),
                previewChange("controller", GitChangeKind.ADDED, "src/main/kotlin/web/UserController.kt", 48, null),
                previewChange("build", GitChangeKind.MODIFIED, "build.gradle.kts", 1, 1),
                previewChange("legacy", GitChangeKind.DELETED, "src/main/kotlin/legacy/LegacyMapper.kt", null, 61),
            ),
        ),
    ),
)

private fun previewChange(id: String, kind: GitChangeKind, path: String, added: Int?, deleted: Int?) = GitChange(
    id = id,
    kind = kind,
    beforePath = path.takeIf { kind == GitChangeKind.DELETED },
    afterPath = path.takeUnless { kind == GitChangeKind.DELETED },
    fileName = path.substringAfterLast('/'),
    parentPath = path.substringBeforeLast('/', missingDelimiterValue = "(project root)"),
    addedLines = added,
    deletedLines = deleted,
)

private fun previewDiff(repositoryId: String, changeId: String, binary: Boolean, unavailable: Boolean) = GitDiff(
    repositoryId = repositoryId,
    changeId = changeId,
    beforeContent = "class UserService {\n    fun delete() = repository.delete()\n}".takeUnless { binary },
    afterContent = "class UserService {\n    fun delete() {\n        if (repository.exists()) repository.delete()\n    }\n}"
        .takeUnless { binary },
    isBinary = binary,
    unavailableReason = "The computer did not provide this diff.".takeIf { unavailable },
)

private fun previewCommit(id: String, shortId: String, subject: String, author: String) =
    GitCommitSummary(id, shortId, subject, author, 0, author, 0, emptyList())

private fun previewCommits() = listOf(
    previewCommit("commit-1", "a1b2c3d", "Add existence guard to delete()", "You"),
    previewCommit("commit-2", "e4f5a6b", "Merge branch 'feature/pagination'", "Maya Chen"),
    previewCommit("commit-3", "d7e8f9a", "Handle retryable API failures", "Alex Kim"),
)

private fun previewBranches(repositoryId: String) = GitBranches(
    repositoryId = repositoryId,
    revision = PREVIEW_REVISION,
    localBranches = listOf(
        GitBranch(
            "main",
            "refs/heads/main",
            "main",
            isCurrent = true,
            trackedUpstream = "origin/main",
            aheadCount = 2,
            behindCount = 0,
        ),
        GitBranch(
            "develop",
            "refs/heads/develop",
            "develop",
            trackedUpstream = "origin/develop",
            aheadCount = 0,
            behindCount = 3,
            availableActions = setOf(GitAction.CHECKOUT),
        ),
        GitBranch(
            "release",
            "refs/heads/release/1.4",
            "release/1.4",
            aheadCount = 0,
            behindCount = 0,
            availableActions = setOf(GitAction.CHECKOUT),
        ),
    ),
    remoteBranches = listOf(
        GitBranch("origin-main", "refs/remotes/origin/main", "origin/main", remoteName = "origin"),
        GitBranch("origin-develop", "refs/remotes/origin/develop", "origin/develop", remoteName = "origin"),
        GitBranch(
            "origin-pagination",
            "refs/remotes/origin/feature/pagination",
            "origin/feature/pagination",
            remoteName = "origin",
        ),
    ),
)

private fun previewPush(repositoryId: String) = GitPushPreview(
    repositoryId = repositoryId,
    revision = PREVIEW_REVISION,
    pushTargetId = "origin-main",
    localBranch = "main",
    upstreamBranch = "origin/main",
    remoteName = "origin",
    remoteDisplayUrl = "github.com/example/demo",
    commits = listOf(
        previewCommit("commit-1", "a1b2c3d", "Add existence guard to delete()", "You"),
        previewCommit("commit-2", "e4f5a6b", "Merge branch 'feature/pagination'", "You"),
    ),
    commitsTruncated = false,
)

private const val PREVIEW_REVISION = 4L
private const val GIT_PREVIEW_MAX_CHANGES = 500
private const val GIT_PREVIEW_MAX_MESSAGE_LENGTH = 16_000
