package app.tennyo.mobile.feature.git


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitStateTest {
    @Test
    fun `repository selection clears target state and cannot be overwritten by another root`() {
        val state = GitState(
            selectedRepositoryId = "old",
            selectedDiff = GitDiffKey("old", "change"),
            pushPreviews = mapOf("old" to preview("old", 4)),
        ).selectRepository("new")

        assertEquals("new", state.selectedRepositoryId)
        assertNull(state.selectedDiff)
        assertTrue(state.pushPreviews.isEmpty())
        assertFalse(state.canAcceptOverview("old", status("old", 5)))
        assertTrue(state.canAcceptOverview("new", status("new", 5)))
    }

    @Test
    fun `new invalidation makes preview stale and accepts an overview newer than the shown one`() {
        val state = GitState(
            overview = status("repo", 8),
            selectedRepositoryId = "repo",
            appliedOverviewRevision = 8,
            pushPreviews = mapOf("repo" to preview("repo", 8)),
        ).invalidate(10)

        assertTrue(state.overviewStale)
        assertTrue(state.pushPreviews.isEmpty())
        assertFalse(state.canAcceptOverview("repo", status("repo", 7)))
        assertTrue(state.canAcceptOverview("repo", status("repo", 9)))
        assertTrue(state.acceptOverview(status("repo", 9)).overviewStale)
        assertTrue(state.canAcceptOverview("repo", status("repo", 10)))
    }

    @Test
    fun `history pages from different revisions are not mixed`() {
        val first = GitHistoryPage("repo", 4, listOf(commit("one")), nextCursor = "next")
        val staleNext = GitHistoryPage("repo", 5, listOf(commit("two")))

        val state = GitState(
            histories = mapOf("repo" to GitHistoryState(first)),
        ).acceptHistoryPage("repo", staleNext, append = true)

        assertEquals(listOf("two"), state.histories.getValue("repo").response.commits.map { it.id })
    }

    @Test
    fun `late diff response is cached without restoring a closed destination`() {
        val key = GitDiffKey("repo", "change")
        val response = GitDiff("repo", "change", beforeContent = "old", afterContent = "new")
        val presentation = response.toPresentation()

        val state = GitState(selectedDiff = null)
            .acceptDiff(key, response, presentation)

        assertNull(state.selectedDiff)
        assertEquals(response, state.diffs[key])
        assertEquals(presentation, state.diffPresentations[key])
    }

    @Test
    fun `late diff response does not replace a newer destination`() {
        val lateKey = GitDiffKey("repo", "old")
        val currentKey = GitDiffKey("repo", "new")
        val response = GitDiff("repo", "old", beforeContent = "old", afterContent = "new")

        val state = GitState(selectedDiff = currentKey)
            .acceptDiff(lateKey, response, response.toPresentation())

        assertEquals(currentKey, state.selectedDiff)
    }

    @Test
    fun `overview selects first repository when backend has no selection`() {
        val response = GitStatus(
            repositories = listOf(
                GitRepository("one", "One"),
                GitRepository("two", "Two"),
            ),
            selectedRepositoryId = null,
            revision = 1,
        )

        val state = GitState().acceptOverview(response)

        assertEquals("one", state.selectedRepositoryId)
    }

    @Test
    fun `overview replaces a selection that is no longer present`() {
        val response = GitStatus(
            repositories = listOf(GitRepository("remaining", "Remaining")),
            selectedRepositoryId = null,
            revision = 2,
        )

        val state = GitState(selectedRepositoryId = "removed").acceptOverview(response)

        assertEquals("remaining", state.selectedRepositoryId)
    }

    @Test
    fun `unknown mutation outcome blocks another mutation until reconciliation`() {
        val state = GitState(
            mutationOutcome = mapOf("repo" to GitMutationOutcome.Unknown),
        )

        assertTrue(state.isMutationBlocked("repo"))
        assertFalse(state.isMutationBlocked("other"))
    }

    @Test
    fun `push requires a positive known ahead count`() {
        fun repository(ahead: Int?) = GitRepository(
            id = "repo",
            rootName = "Repo",
            availableActions = setOf(GitAction.PUSH),
            aheadCount = ahead,
        )

        assertFalse(repository(null).hasPushableCommits)
        assertFalse(repository(0).hasPushableCommits)
        assertTrue(repository(2).hasPushableCommits)
    }

    @Test
    fun `nested Git destinations are identified for system back interception`() {
        assertFalse(GitState().hasNestedDestination)
        assertTrue(GitState(selectedDiff = GitDiffKey("repo", "change")).hasNestedDestination)
        assertTrue(GitState(pushPreviews = mapOf("repo" to preview("repo", 4))).hasNestedDestination)
    }

    @Test
    fun `overview refresh policy bypasses backoff and deduplicates an active request`() {
        assertTrue(shouldStartGitOverviewRequest(manual = true, backoffAllows = false, active = false))
        assertFalse(shouldStartGitOverviewRequest(manual = true, backoffAllows = true, active = true))
        assertFalse(shouldStartGitOverviewRequest(manual = false, backoffAllows = false, active = false))
        assertTrue(shouldStartGitOverviewRequest(manual = false, backoffAllows = true, active = false))
    }

    @Test
    fun `initial Git state remains visibly pending until overview succeeds or fails`() {
        assertTrue(GitState().isOverviewPending)
        assertTrue(
            GitState(
                loadingKeys = setOf(GitRequestKey.Overview),
            ).isOverviewPending,
        )
        assertFalse(
            GitState(
                requestFailures = mapOf(
                    GitRequestKey.Overview to GitFault.Rejected(GitRejection.PLATFORM_FAILURE),
                ),
            ).isOverviewPending,
        )
        assertFalse(GitState(overview = status("repo", 4)).isOverviewPending)
    }

    @Test
    fun `read failures stay isolated and pagination resets only its matching history`() {
        val first = GitHistoryState(
            GitHistoryPage("one", 4, listOf(commit("one")), nextCursor = "next-one"),
            loadingNextPage = true,
        )
        val second = GitHistoryState(
            GitHistoryPage("two", 4, listOf(commit("two")), nextCursor = "next-two"),
            loadingNextPage = true,
        )
        val historyKey = GitRequestKey.HistoryNext("one", "next-one")
        val branchesKey = GitRequestKey.Branches("two")
        val state = GitState(
            histories = mapOf("one" to first, "two" to second),
            loadingKeys = setOf(historyKey, branchesKey),
        ).failRequest(historyKey, GitFault.Rejected(GitRejection.PLATFORM_FAILURE))

        assertFalse(state.histories.getValue("one").loadingNextPage)
        assertTrue(state.histories.getValue("two").loadingNextPage)
        assertEquals(GitFault.Rejected(GitRejection.PLATFORM_FAILURE), state.requestFailures[historyKey])
        assertFalse(branchesKey in state.requestFailures)
        assertTrue(branchesKey in state.loadingKeys)
    }

    @Test
    fun `overview initializes and reconciles commit draft selections`() {
        val first = statusWithChanges(4, listOf("one", "two"))
        val accepted = GitState(selectedRepositoryId = "repo").acceptOverview(first)
        assertEquals(setOf("one", "two"), accepted.commitDrafts.getValue("repo").selectedChangeIds)

        val edited = accepted.copy(
            commitDrafts = mapOf(
                "repo" to accepted.commitDrafts.getValue("repo").copy(
                    selectedChangeIds = setOf("two"), message = "Keep this message"
                )
            )
        ).acceptOverview(statusWithChanges(5, listOf("two", "three")))

        assertEquals(setOf("two"), edited.commitDrafts.getValue("repo").selectedChangeIds)
        assertEquals("Keep this message", edited.commitDrafts.getValue("repo").message)
        assertFalse(edited.commitDrafts.getValue("repo").stale)
    }

    @Test
    fun `invalidation marks draft stale and commit validation checks all inputs`() {
        val state = GitState(selectedRepositoryId = "repo")
            .acceptOverview(statusWithChanges(4, listOf("one")))
        val ready =
            state.copy(commitDrafts = mapOf("repo" to state.commitDrafts.getValue("repo").copy(message = "Commit")))
        assertTrue(ready.canCommit("repo"))
        assertFalse(ready.invalidate(5).canCommit("repo"))
        assertFalse(ready.copy(mutatingRepositories = setOf("repo")).canCommit("repo"))
    }

    private fun status(repositoryId: String, revision: Long) = GitStatus(
        repositories = listOf(GitRepository(repositoryId, repositoryId)),
        selectedRepositoryId = repositoryId,
        revision = revision,
    )

    private fun statusWithChanges(revision: Long, changes: List<String>) = GitStatus(
        repositories = listOf(
            GitRepository(
                "repo",
                "repo",
                changeLists = listOf(GitChangeList("default", "Default", changes.map {
                    GitChange(it, GitChangeKind.MODIFIED, fileName = "$it.kt", parentPath = "src")
                })),
                commitCapability = GitCommitLimits(500, 16_000),
            )
        ),
        selectedRepositoryId = "repo",
        revision = revision,
    )

    private fun preview(repositoryId: String, revision: Long) = GitPushPreview(
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

    private fun commit(id: String) = GitCommitSummary(
        id = id,
        shortId = id,
        subject = id,
        authorName = "Author",
        authoredAtEpochMillis = 0,
        committerName = "Author",
        committedAtEpochMillis = 0,
        parentIds = emptyList(),
    )
}
