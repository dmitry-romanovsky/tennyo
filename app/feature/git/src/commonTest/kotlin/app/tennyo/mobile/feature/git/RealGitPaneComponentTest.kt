package app.tennyo.mobile.feature.git

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RealGitPaneComponentTest {
    private val source = FakeGitSource()
    private val pane = RealGitPaneComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
        source = source,
        mainContext = Dispatchers.Unconfined,
        diffContext = Dispatchers.Unconfined,
    )

    @Test
    fun anAutomaticRefreshWaitsOutABackingOffSourceButAManualOneDoesNot() {
        source.automaticRefreshAllowed = false

        pane.refresh()
        assertEquals(0, source.statusCalls)

        pane.onIntent(GitIntent.Refresh)
        assertEquals(1, source.statusCalls)
        assertEquals("repo", pane.state.value.selectedRepositoryId)
    }

    @Test
    fun aStatusTheComputerNeverAnswersEndsInATimeoutInsteadOfLoadingForever() = runTest {
        source.statusHangs = true
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = source,
            mainContext = StandardTestDispatcher(testScheduler),
        )

        pane.onIntent(GitIntent.Refresh)
        advanceUntilIdle()

        assertEquals(GitFault.Rejected(GitRejection.TIMED_OUT), pane.state.value.requestFailures[GitRequestKey.Overview])
        assertFalse(pane.state.value.isOverviewPending)
    }

    @Test
    fun aSuccessfulCommitDropsTheDraftAndRefreshes() {
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.Commit("repo"))

        assertEquals(listOf("a"), source.committed.single().changeIds)
        assertEquals("", pane.state.value.commitDrafts.getValue("repo").message)
        assertEquals(2, source.statusCalls)
        assertFalse(pane.state.value.isMutating("repo"))
    }

    @Test
    fun aCommitWhoseAnswerIsLostBlocksFurtherMutationsUntilTheOutcomeIsKnown() {
        source.commitFault = GitFault.Network
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.Commit("repo"))

        assertEquals(GitMutationOutcome.Unknown, pane.state.value.mutationOutcome["repo"])
        assertTrue(pane.state.value.isMutationBlocked("repo"))
    }

    @Test
    fun backClosesTheCheckoutSheetBeforeLeavingThePane() {
        pane.onIntent(GitIntent.OpenBranchAction("repo", "branch", "feature", expectedRevision = 1))

        assertTrue(pane.onBack())
        assertNull(pane.state.value.checkoutSelection)
        assertFalse(pane.onBack())
    }

    @Test
    fun rollingBackAFileAsksFirstAndSendsItWithTheRevisionItWasChosenAt() {
        pane.onIntent(GitIntent.Refresh)

        pane.onIntent(GitIntent.RollbackChange("repo", "a"))

        assertTrue(source.rolledBack.isEmpty())
        val prompt = pane.state.value.prompt as GitPrompt.ConfirmDiscard
        assertEquals(listOf("a"), (prompt.discard as GitDiscard.Changes).changes.map(GitChange::id))

        pane.onIntent(GitIntent.ConfirmDiscard)

        assertEquals(GitCommitSelection(listOf("a"), expectedRevision = 1), source.rolledBack.single())
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun resettingToHeadAsksFirstAndDismissingRunsNothing() {
        pane.onIntent(GitIntent.Refresh)

        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.RESET_HEAD))
        assertEquals(GitDiscard.AllTracked, (pane.state.value.prompt as GitPrompt.ConfirmDiscard).discard)
        pane.onIntent(GitIntent.DismissPrompt)
        assertTrue(source.projectActions.isEmpty())

        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.RESET_HEAD))
        pane.onIntent(GitIntent.ConfirmDiscard)
        assertEquals(listOf(GitProjectAction.RESET_HEAD), source.projectActions)
    }

    @Test
    fun aRolledBackFileClosesItsOpenDiff() {
        pane.onIntent(GitIntent.Refresh)
        source.diffAnswer = GitDiff("repo", "a", afterPath = "a", afterContent = "x")
        pane.onIntent(GitIntent.OpenDiff("repo", "a"))

        pane.onIntent(GitIntent.RollbackChange("repo", "a"))
        pane.onIntent(GitIntent.ConfirmDiscard)

        assertNull(pane.state.value.selectedDiff)
    }

    @Test
    fun commitAndPushOpensThePushPreviewOnlyOnceTheCommitSucceeded() {
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.CommitAndPush("repo"))

        assertEquals(listOf("a"), source.committed.single().changeIds)
        assertEquals(preview("repo", 1), pane.state.value.pushPreviews["repo"])
    }

    @Test
    fun commitAndPushDoesNothingWhereTheRepositoryCannotPush() {
        source.pushable = false
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.CommitAndPush("repo"))

        assertTrue(source.committed.isEmpty())
        assertNull(pane.state.value.pushPreviews["repo"])
    }

    @Test
    fun aRefusedCommitAndPushOpensNoPushPreview() {
        source.commitOutcome = GitOutcome.REJECTED
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.CommitAndPush("repo"))

        assertEquals(0, source.pushPreviewCalls)
        assertTrue(pane.state.value.pushPreviews.isEmpty())
    }

    @Test
    fun aCommitWhoseAnswerIsLostOpensNoPushPreview() {
        source.commitFault = GitFault.Network
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "Fix"))

        pane.onIntent(GitIntent.CommitAndPush("repo"))

        assertEquals(0, source.pushPreviewCalls)
    }

    @Test
    fun rollingBackSelectedFilesAsksOnceAndSendsEveryIdTogether() {
        source.changeIds = listOf("a", "b", "c")
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ToggleChange("repo", "b"))

        pane.onIntent(GitIntent.RollbackSelectedChanges("repo"))

        val prompt = pane.state.value.prompt as GitPrompt.ConfirmDiscard
        assertEquals(listOf("a", "c"), (prompt.discard as GitDiscard.Changes).changes.map(GitChange::id))
        assertTrue(source.rolledBack.isEmpty())

        pane.onIntent(GitIntent.ConfirmDiscard)

        assertEquals(GitCommitSelection(listOf("a", "c"), expectedRevision = 1), source.rolledBack.single())
    }

    @Test
    fun rollingBackSelectedFilesSkipsThoseThatCannotBeRolledBack() {
        source.changeIds = listOf("a", "b")
        source.withoutRollback = setOf("b")
        pane.onIntent(GitIntent.Refresh)

        assertEquals(listOf("a"), pane.state.value.rollbackableSelection("repo").map(GitChange::id))
        pane.onIntent(GitIntent.RollbackSelectedChanges("repo"))
        pane.onIntent(GitIntent.ConfirmDiscard)

        assertEquals(listOf("a"), source.rolledBack.single().changeIds)
    }

    @Test
    fun rollingBackWithNothingRollbackableOrAStaleSelectionAsksNothing() {
        source.changeIds = listOf("a")
        source.withoutRollback = setOf("a")
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.RollbackSelectedChanges("repo"))
        assertNull(pane.state.value.prompt)

        source.withoutRollback = emptySet()
        pane.onIntent(GitIntent.Refresh)
        pane.invalidate(revision = 5, shown = false)
        pane.onIntent(GitIntent.RollbackSelectedChanges("repo"))

        assertTrue(pane.state.value.rollbackableSelection("repo").isEmpty())
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun dismissingAnOutcomeClearsAReceivedOneButNeverAnUnknownOne() {
        val received = GitState(mutationOutcome = mapOf("repo" to GitMutationOutcome.Received(GitOutcome.REJECTED)))
        assertTrue(reduce(received, GitMsg.OutcomeDismissed("repo")).mutationOutcome.isEmpty())

        val unknown = GitState(mutationOutcome = mapOf("repo" to GitMutationOutcome.Unknown))
        assertEquals(unknown, reduce(unknown, GitMsg.OutcomeDismissed("repo")))
    }

    @Test
    fun aSuccessDismissesItselfAfterTheNoticeDurationButAFailureStays() = runTest {
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = source,
            mainContext = StandardTestDispatcher(testScheduler),
        )
        pane.onIntent(GitIntent.Refresh)
        runCurrent()
        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.STASH))
        runCurrent()
        assertEquals(GitMutationOutcome.Received(GitOutcome.SUCCESS), pane.state.value.mutationOutcome["repo"])

        advanceTimeBy(GIT_SUCCESS_NOTICE_DURATION - 1.milliseconds)
        runCurrent()
        assertTrue("repo" in pane.state.value.mutationOutcome)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertFalse("repo" in pane.state.value.mutationOutcome)

        source.projectActionOutcome = GitOutcome.REJECTED
        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.STASH))
        advanceTimeBy(GIT_SUCCESS_NOTICE_DURATION * 2)
        runCurrent()
        assertEquals(GitMutationOutcome.Received(GitOutcome.REJECTED), pane.state.value.mutationOutcome["repo"])
    }

    @Test
    fun aNewerFailureIsNotClearedByAnEarlierSuccessTimer() = runTest {
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = source,
            mainContext = StandardTestDispatcher(testScheduler),
        )
        pane.onIntent(GitIntent.Refresh)
        runCurrent()
        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.STASH))
        runCurrent()
        source.projectActionOutcome = GitOutcome.REJECTED
        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.STASH))
        runCurrent()

        advanceTimeBy(GIT_SUCCESS_NOTICE_DURATION * 2)
        runCurrent()

        assertEquals(GitMutationOutcome.Received(GitOutcome.REJECTED), pane.state.value.mutationOutcome["repo"])
    }

    @Test
    fun dismissingTheDestinationSheetClosesTheDiffAndTheCommitTogether() {
        val open = GitState(selectedDiff = GitDiffKey("repo", "a", "c1"), selectedCommit = GitCommitKey("repo", "c1"))

        val closed = reduce(open, GitMsg.DestinationDismissed)

        assertNull(closed.selectedDiff)
        assertNull(closed.selectedCommit)
        assertEquals(GitCommitKey("repo", "c1"), reduce(open, GitMsg.DestinationClosed).selectedCommit)
    }

    @Test
    fun aFailedReadIsRecordedAgainstItsRequest() {
        source.statusFault = GitFault.Rejected(GitRejection.BUSY)

        pane.onIntent(GitIntent.Refresh)

        assertEquals(GitFault.Rejected(GitRejection.BUSY), pane.state.value.requestFailures[GitRequestKey.Overview])
    }
}

private class FakeGitSource : GitRepositorySource {
    var automaticRefreshAllowed = true
    var statusCalls = 0
    var statusFault: GitFault? = null
    var statusHangs = false
    var commitFault: GitFault? = null
    val committed = mutableListOf<GitCommitSelection>()
    val rolledBack = mutableListOf<GitCommitSelection>()
    val projectActions = mutableListOf<GitProjectAction>()
    var diffAnswer: GitDiff? = null
    var commitOutcome = GitOutcome.SUCCESS
    var projectActionOutcome = GitOutcome.SUCCESS
    var changeIds = listOf("a")
    var withoutRollback = emptySet<String>()
    var pushable = true
    var pushPreviewCalls = 0

    override val capabilities = GitSourceCapabilities(projectSettings = true)

    override fun automaticRefreshAllowed(): Boolean = automaticRefreshAllowed

    override suspend fun status(repositoryId: String?): GitStatus {
        statusCalls++
        statusFault?.let { throw it }
        if (statusHangs) awaitCancellation()
        val status = statusWithChanges(revision = 1, changes = changeIds)
        return status.copy(
            repositories = status.repositories.map { repository ->
                repository.copy(
                    availableActions = if (pushable) {
                        repository.availableActions
                    } else {
                        repository.availableActions - GitAction.PUSH
                    },
                    changeLists = repository.changeLists.map { list ->
                        list.copy(
                            changes = list.changes.map {
                                if (it.id in withoutRollback) it.copy(availableActions = emptySet()) else it
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun commit(repositoryId: String, selection: GitCommitSelection, message: String): GitOperationResult {
        commitFault?.let { throw it }
        committed += selection
        return GitOperationResult(repositoryId, commitOutcome)
    }

    override suspend fun rollback(repositoryId: String, selection: GitCommitSelection): GitOperationResult {
        rolledBack += selection
        return GitOperationResult(repositoryId, GitOutcome.SUCCESS)
    }

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult {
        projectActions += action
        return GitOperationResult(repositoryId, projectActionOutcome)
    }

    override suspend fun diff(repositoryId: String, changeId: String): GitDiff = diffAnswer ?: error("unused")
    override suspend fun history(repositoryId: String, cursor: String?, limit: Int): GitHistoryPage = error("unused")
    override suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails = error("unused")
    override suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff = error("unused")
    override suspend fun branches(repositoryId: String): GitBranches = error("unused")
    override suspend fun checkout(repositoryId: String, target: GitCheckoutTarget): GitOperationResult = error("unused")
    override suspend fun pushPreview(repositoryId: String): GitPushPreview {
        pushPreviewCalls++
        return preview(repositoryId, revision = 1)
    }

    override suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult =
        error("unused")
}
