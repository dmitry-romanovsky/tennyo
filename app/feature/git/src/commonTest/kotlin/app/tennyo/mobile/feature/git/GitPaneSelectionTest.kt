package app.tennyo.mobile.feature.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pane's selection changes: branch folders, change selection, push previews and the checkout sheet. */
class GitPaneSelectionTest {
    @Test
    fun toggleBranchFolderExpandsThenCollapses() {
        val expanded = reduce(GitState(), GitMsg.BranchFolderToggled("folder-1"))
        assertEquals(setOf("folder-1"), expanded.expandedBranchFolders)

        assertEquals(emptySet(), reduce(expanded, GitMsg.BranchFolderToggled("folder-1")).expandedBranchFolders)
    }

    @Test
    fun selectAllChangesSelectsEveryChangeInTheRepository() {
        val state = GitState(
            overview = statusWithChanges(revision = 1, changes = listOf("a", "b", "c")),
            commitDrafts = mapOf("repo" to GitCommitDraft(selectedChangeIds = emptySet(), revision = 1)),
        )

        val selected = reduce(state, GitMsg.AllChangesSelected("repo", selected = true))

        assertEquals(setOf("a", "b", "c"), selected.commitDrafts.getValue("repo").selectedChangeIds)
    }

    @Test
    fun selectAllChangesFalseClearsSelection() {
        val state = GitState(
            overview = statusWithChanges(revision = 1, changes = listOf("a", "b")),
            commitDrafts = mapOf("repo" to GitCommitDraft(selectedChangeIds = setOf("a", "b"), revision = 1)),
        )

        val cleared = reduce(state, GitMsg.AllChangesSelected("repo", selected = false))

        assertTrue(cleared.commitDrafts.getValue("repo").selectedChangeIds.isEmpty())
    }

    @Test
    fun openBranchActionSetsCheckoutSelectionAndClearsPushPreview() {
        val selection = GitCheckoutSelection("repo", "branch-1", "feature/x", expectedRevision = 7L)

        val state = reduce(
            GitState(pushPreviews = mapOf("repo" to preview("repo", revision = 1))),
            GitMsg.BranchActionOpened(selection),
        )

        assertEquals(selection, state.checkoutSelection)
        assertTrue(state.pushPreviews.isEmpty())
    }

    @Test
    fun aChangeDiffReplacesTheOpenCommitButACommitFileDiffOpensOnTopOfIt() {
        val commit = GitState(selectedCommit = GitCommitKey("repo", "c1"))

        assertNull(reduce(commit, GitMsg.DiffOpened(GitDiffKey("repo", "change"))).selectedCommit)
        assertEquals(
            GitCommitKey("repo", "c1"),
            reduce(commit, GitMsg.DiffOpened(GitDiffKey("repo", "file", commitId = "c1"))).selectedCommit,
        )
    }

    @Test
    fun losingTheSourceDropsEverything() {
        val state = GitState(selectedRepositoryId = "repo", overview = statusWithChanges(1, listOf("a")))

        assertEquals(GitState(), reduce(state, GitMsg.Reset))
    }
}

internal fun statusWithChanges(revision: Long, changes: List<String>) = GitStatus(
    repositories = listOf(
        GitRepository(
            "repo",
            "repo",
            changeLists = listOf(
                GitChangeList(
                    "default",
                    "Default",
                    changes.map {
                        GitChange(
                            it,
                            GitChangeKind.MODIFIED,
                            fileName = "$it.kt",
                            parentPath = "src",
                            availableActions = setOf(GitAction.VIEW_DIFF, GitAction.ROLLBACK),
                        )
                    },
                ),
            ),
            commitCapability = GitCommitLimits(500, 16_000),
            availableActions = setOf(GitAction.REFRESH, GitAction.PUSH),
        ),
    ),
    selectedRepositoryId = "repo",
    revision = revision,
)

internal fun preview(repositoryId: String, revision: Long) = GitPushPreview(
    repositoryId = repositoryId,
    revision = revision,
    pushTargetId = "target",
    localBranch = "main",
    upstreamBranch = "origin/main",
    remoteName = "origin",
    remoteDisplayUrl = "https://example.com/repo.git",
    commits = emptyList(),
    commitsTruncated = false,
)
