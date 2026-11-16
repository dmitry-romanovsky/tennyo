package app.tennyo.mobile.feature.git

import app.tennyo.mobile.design.PaneNotice
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitPromptTest {
    private val source = PromptingGitSource()
    private val pane = RealGitPaneComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
        source = source,
        mainContext = Dispatchers.Unconfined,
        diffContext = Dispatchers.Unconfined,
    )

    @Test
    fun theRemoteEditorSavesRenamesAndRemovesButNeverSendsABadName() {
        pane.onIntent(GitIntent.OpenRemote("repo", remote = null))
        pane.onIntent(GitIntent.SaveRemote("my remote", "https://example.com/a.git"))
        assertIs<GitPrompt.EditRemote>(pane.state.value.prompt, "a name with a space keeps the editor open")

        pane.onIntent(GitIntent.SaveRemote(" origin ", " https://example.com/a.git "))
        pane.onIntent(GitIntent.OpenRemote("repo", GitRemote("origin", "https://example.com/a.git")))
        pane.onIntent(GitIntent.RemoveRemote)
        pane.onIntent(GitIntent.RunProjectAction("repo", GitProjectAction.STASH))

        assertEquals(
            listOf("remote:null>origin=https://example.com/a.git", "remove:origin", "action:STASH"),
            source.operations,
        )
    }

    @Test
    fun theProjectTabIsOfferedOnlyWhereTheSourceHasIt() {
        assertEquals(GitSection.entries, GitSourceCapabilities(projectSettings = true).sections())
        assertEquals(GitSection.entries - GitSection.Project, GitSourceCapabilities().sections())
    }

    @Test
    fun aDivergedPullOffersAMergeThatRunsOnConfirm() {
        source.pullFaults += GitFault.NonFastForward

        pane.onIntent(GitIntent.Pull("repo"))

        assertEquals(GitPrompt.Diverged("repo"), pane.state.value.prompt)
        assertFalse(pane.state.value.isMutationBlocked("repo"))

        pane.onIntent(GitIntent.ConfirmMerge)

        assertNull(pane.state.value.prompt)
        assertEquals(listOf("pull", "merge"), source.operations)
    }

    @Test
    fun aRefusedTransferAsksForCredentialsAndRetriesOnceWithThem(): Unit = runBlocking {
        source.fetchFaults += GitFault.AuthRequired("example.com")
        source.fetchFaults += GitFault.AuthRequired("example.com")

        pane.onIntent(GitIntent.Fetch("repo"))
        val prompt = assertIs<GitPrompt.Credentials>(pane.state.value.prompt)
        assertEquals("example.com", prompt.host)

        pane.onIntent(GitIntent.SubmitCredentials("octo", "token"))

        assertEquals(listOf("fetch", "credentials:example.com:octo", "fetch"), source.operations)
        assertNull(pane.state.value.prompt, "rejected credentials are not asked for again in a loop")
        assertEquals(PaneNotice.Failure(GitFault.AuthRequired("example.com").text), pane.notices.first())
    }

    @Test
    fun aCommitWithoutAnAuthorAsksForOneAndCommitsAfterSaving() {
        source.commitFaults += GitFault.IdentityMissing
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "First"))

        pane.onIntent(GitIntent.Commit("repo"))
        assertIs<GitPrompt.Identity>(pane.state.value.prompt)

        pane.onIntent(GitIntent.SubmitIdentity(" Ada ", "ada@example.com"))

        assertEquals(listOf("commit", "identity:Ada:ada@example.com", "commit"), source.operations)
        assertNull(pane.state.value.commitDrafts.getValue("repo").message.takeIf { it.isNotEmpty() })
    }

    @Test
    fun commitAndPushThatFirstNeedsAnAuthorStillPushesAfterTheAuthorIsSaved() {
        source.commitFaults += GitFault.IdentityMissing
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.ChangeCommitMessage("repo", "First"))

        pane.onIntent(GitIntent.CommitAndPush("repo"))
        assertNull(pane.state.value.pushPreviews["repo"])
        pane.onIntent(GitIntent.SubmitIdentity("Ada", "ada@example.com"))

        assertEquals(listOf("commit", "identity:Ada:ada@example.com", "commit", "pushPreview"), source.operations)
        assertNotNull(pane.state.value.pushPreviews["repo"])
    }

    @Test
    fun editingTheAuthorOpensThePromptWithTheCurrentOneAndSavingJustReloadsTheProject() {
        source.projectIdentity = GitIdentity("Ada", "ada@example.com")
        pane.onIntent(GitIntent.Refresh)
        pane.onIntent(GitIntent.SelectSection(GitSection.Project))

        pane.onIntent(GitIntent.EditIdentity("repo"))
        assertEquals(
            GitPrompt.Identity("repo", retry = null, current = GitIdentity("Ada", "ada@example.com")),
            pane.state.value.prompt,
        )

        source.projectIdentity = GitIdentity("Grace", "grace@example.com")
        pane.onIntent(GitIntent.SubmitIdentity(" Grace ", "grace@example.com"))

        assertEquals(listOf("projectInfo", "identity:Grace:grace@example.com", "projectInfo"), source.operations)
        assertEquals(GitIdentity("Grace", "grace@example.com"), pane.state.value.projectInfos["repo"]?.identity)
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun editingTheAuthorDoesNothingWhereTheSourceCannotStoreOne() {
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = PromptingGitSource(GitSourceCapabilities()),
            mainContext = Dispatchers.Unconfined,
            diffContext = Dispatchers.Unconfined,
        )

        pane.onIntent(GitIntent.EditIdentity("repo"))

        assertNull(pane.state.value.prompt)
    }

    @Test
    fun aKnownRefusalLeavesTheRepositoryFreeAndTellsTheUser(): Unit = runBlocking {
        source.fetchFaults += GitFault.Rejected(GitRejection.MISSING_UPSTREAM)

        pane.onIntent(GitIntent.Fetch("repo"))

        assertFalse(pane.state.value.isMutationBlocked("repo"))
        assertEquals(
            PaneNotice.Failure(GitFault.Rejected(GitRejection.MISSING_UPSTREAM).text),
            pane.notices.first(),
        )
    }

    @Test
    fun backClosesAnOpenPromptFirst() {
        pane.onIntent(GitIntent.OpenNewBranch("repo"))
        assertNotNull(pane.state.value.prompt)

        assertTrue(pane.onBack())
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun aNewBranchIsCreatedUnderItsTrimmedName() {
        pane.onIntent(GitIntent.OpenNewBranch("repo"))
        pane.onIntent(GitIntent.CreateBranch("repo", " feature/login "))

        assertEquals(listOf("branch:feature/login"), source.operations)
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun aBranchIsDeletedOnlyAfterTheUserConfirms() {
        pane.onIntent(GitIntent.DeleteBranch("repo", "refs/heads/old", "old", expectedRevision = 3))
        assertEquals(
            GitPrompt.ConfirmDiscard("repo", GitDiscard.Branch("refs/heads/old", "old", 3)),
            pane.state.value.prompt,
        )
        assertEquals(emptyList(), source.operations)

        pane.onIntent(GitIntent.ConfirmDiscard)

        assertEquals(listOf("delete:refs/heads/old@3"), source.operations.filterNot { it == "branches" })
        assertNull(pane.state.value.prompt)
    }

    @Test
    fun theBranchListOpensOverTheSectionAndBackClosesIt() {
        pane.onIntent(GitIntent.OpenBranches("repo"))
        pane.onIntent(GitIntent.ChangeBranchSearch("feat"))
        assertTrue(pane.state.value.branchesOpen)
        assertEquals(GitSection.Changes, pane.state.value.selectedSection)
        assertEquals(listOf("branches"), source.operations)

        assertTrue(pane.onBack())

        assertFalse(pane.state.value.branchesOpen)
        assertEquals("", pane.state.value.branchSearchQuery, "a closed list forgets its search")
    }

    @Test
    fun openingAFileInTheEditorClosesItsDiff() {
        val opened = mutableListOf<String>()
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = source,
            mainContext = Dispatchers.Unconfined,
            diffContext = Dispatchers.Unconfined,
            openInEditor = { opened += it },
        )
        assertTrue(pane.state.value.opensFilesInEditor)
        pane.onIntent(GitIntent.OpenDiff("repo", "a"))

        pane.onIntent(GitIntent.OpenInEditor("src/a.kt"))

        assertEquals(listOf("src/a.kt"), opened)
        assertNull(pane.state.value.selectedDiff)
    }
}

class GitCapabilityVisibilityTest {
    @Test
    fun faultsOfferPromptsOnlyForWhatTheSourceSupports() {
        val none = GitSourceCapabilities()
        val all = GitSourceCapabilities(pull = true, credentials = true, identity = true)
        val auth = GitFault.AuthRequired("host")

        assertNull(auth.prompt("repo", GitMutation.Fetch, none, credentialsSupplied = false))
        assertIs<GitPrompt.Credentials>(auth.prompt("repo", GitMutation.Fetch, all, credentialsSupplied = false))
        assertNull(auth.prompt("repo", GitMutation.Fetch, all, credentialsSupplied = true))
        assertNull(GitFault.NonFastForward.prompt("repo", GitMutation.Push("t", 1), all, false))
        assertIs<GitPrompt.Diverged>(GitFault.NonFastForward.prompt("repo", GitMutation.Pull, all, false))
    }

    @Test
    fun aSourceWithoutBranchCreationIgnoresTheRequest() {
        val pane = RealGitPaneComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            source = PromptingGitSource(GitSourceCapabilities()),
            mainContext = Dispatchers.Unconfined,
            diffContext = Dispatchers.Unconfined,
        )

        pane.onIntent(GitIntent.OpenNewBranch("repo"))

        assertNull(pane.state.value.prompt)
    }
}

private class PromptingGitSource(
    override val capabilities: GitSourceCapabilities = GitSourceCapabilities(
        fetch = true,
        pull = true,
        createBranch = true,
        credentials = true,
        identity = true,
        projectSettings = true,
    ),
) : GitRepositorySource {
    val operations = mutableListOf<String>()
    val fetchFaults = ArrayDeque<GitFault>()
    val pullFaults = ArrayDeque<GitFault>()
    val commitFaults = ArrayDeque<GitFault>()
    var projectIdentity: GitIdentity? = null

    override suspend fun status(repositoryId: String?): GitStatus =
        statusWithChanges(revision = 1, changes = listOf("a")).let { status ->
            status.copy(
                repositories = status.repositories.map { it.copy(commitCapability = GitCommitLimits(10, 100)) },
            )
        }

    override suspend fun fetch(repositoryId: String): GitOperationResult = operation("fetch", fetchFaults)
    override suspend fun pull(repositoryId: String): GitOperationResult = operation("pull", pullFaults)
    override suspend fun merge(repositoryId: String): GitOperationResult = operation("merge", ArrayDeque())

    override suspend fun createBranch(repositoryId: String, name: String): GitOperationResult =
        operation("branch:$name", ArrayDeque())

    override suspend fun deleteBranch(repositoryId: String, branchId: String, expectedRevision: Long) =
        operation("delete:$branchId@$expectedRevision", ArrayDeque())

    override suspend fun commit(repositoryId: String, selection: GitCommitSelection, message: String) =
        operation("commit", commitFaults)

    override suspend fun saveRemote(repositoryId: String, previousName: String?, remote: GitRemote) =
        operation("remote:$previousName>${remote.name}=${remote.url}", ArrayDeque())

    override suspend fun removeRemote(repositoryId: String, name: String) = operation("remove:$name", ArrayDeque())

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction) =
        operation("action:$action", ArrayDeque())

    override suspend fun useCredentials(host: String, credentials: GitCredentials) {
        operations += "credentials:$host:${credentials.username}"
    }

    override suspend fun saveIdentity(identity: GitIdentity) {
        operations += "identity:${identity.name}:${identity.email}"
    }

    private fun operation(name: String, faults: ArrayDeque<GitFault>): GitOperationResult {
        operations += name
        faults.removeFirstOrNull()?.let { throw it }
        return GitOperationResult("repo", GitOutcome.SUCCESS)
    }

    override suspend fun diff(repositoryId: String, changeId: String): GitDiff = GitDiff(repositoryId, changeId)
    override suspend fun history(repositoryId: String, cursor: String?, limit: Int): GitHistoryPage = error("unused")
    override suspend fun rollback(repositoryId: String, selection: GitCommitSelection): GitOperationResult =
        error("unused")

    override suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails = error("unused")
    override suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff =
        error("unused")

    override suspend fun branches(repositoryId: String): GitBranches {
        operations += "branches"
        return GitBranches(repositoryId, revision = 1, localBranches = emptyList(), remoteBranches = emptyList())
    }
    override suspend fun checkout(repositoryId: String, target: GitCheckoutTarget): GitOperationResult =
        error("unused")

    override suspend fun pushPreview(repositoryId: String): GitPushPreview {
        operations += "pushPreview"
        return preview(repositoryId, revision = 1)
    }

    override suspend fun projectInfo(repositoryId: String): GitProjectInfo {
        operations += "projectInfo"
        return GitProjectInfo("repo", "main", null, projectIdentity, emptyList(), stashCount = 0, tagCount = 0)
    }

    override suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult =
        error("unused")
}
