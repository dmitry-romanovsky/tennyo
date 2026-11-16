package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitAction
import app.tennyo.mobile.feature.git.GitChange
import app.tennyo.mobile.feature.git.GitChangeKind
import app.tennyo.mobile.feature.git.GitCheckoutTarget
import app.tennyo.mobile.feature.git.GitCommitSelection
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitOutcome
import app.tennyo.mobile.feature.git.GitProjectAction
import app.tennyo.mobile.feature.git.GitRejection
import app.tennyo.mobile.feature.git.GitStatus
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JGitRepositorySourceTest {
    private val repositories = GitTestRepositories()

    @AfterTest
    fun cleanUp() = repositories.close()

    @Test
    fun statusReportsEveryKindOfWorkingTreeChange(): Unit = runBlocking {
        repositories.initWithCommit(
            "demo",
            mapOf("modified.txt" to "one\n", "deleted.txt" to "gone\n", "moved.txt" to RENAMED_CONTENT),
        ).use { git ->
            repositories.write("demo", "modified.txt", "two\n")
            File(repositories.workspace("demo"), "deleted.txt").delete()
            val workspace = repositories.workspace("demo")
            File(workspace, "moved.txt").renameTo(File(workspace, "renamed.txt"))
            repositories.write("demo", "added.txt", "staged\n")
            git.add().addFilepattern("added.txt").call()
            repositories.write("demo", "untracked.txt", "new\n")
        }

        val kinds = repositories.source("demo").status(null).changes().associate { it.id to it.kind }

        assertEquals(
            mapOf(
                "added.txt" to GitChangeKind.ADDED,
                "deleted.txt" to GitChangeKind.DELETED,
                "modified.txt" to GitChangeKind.MODIFIED,
                "renamed.txt" to GitChangeKind.RENAMED,
                "untracked.txt" to GitChangeKind.UNTRACKED,
            ),
            kinds,
        )
    }

    @Test
    fun committingASelectionLeavesTheOtherChangesUncommitted(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n", "b.txt" to "b\n")).close()
        repositories.write("demo", "a.txt", "a2\n")
        repositories.write("demo", "b.txt", "b2\n")
        val source = repositories.source("demo")
        val status = source.status(null)

        val result = source.commit("demo", GitCommitSelection(listOf("a.txt"), status.revision), "Change a")

        assertEquals(GitOutcome.SUCCESS, result.outcome)
        assertEquals(listOf("b.txt"), source.status(null).changes().map(GitChange::id))
        Git.open(repositories.workspace("demo")).use { git ->
            val head = git.log().setMaxCount(1).call().single()
            assertEquals("Change a", head.fullMessage)
            assertEquals("Ada", head.authorIdent.name)
        }
    }

    @Test
    fun rollingBackRestoresTrackedFilesAndDeletesNewOnesLeavingTheRest(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n", "b.txt" to "b\n")).close()
        repositories.write("demo", "a.txt", "a2\n")
        repositories.write("demo", "b.txt", "b2\n")
        repositories.write("demo", "new.txt", "new\n")
        val source = repositories.source("demo")
        val status = source.status(null)

        val result = source.rollback("demo", GitCommitSelection(listOf("a.txt", "new.txt"), status.revision))

        assertEquals(GitOutcome.SUCCESS, result.outcome)
        val workspace = repositories.workspace("demo")
        assertEquals("a", File(workspace, "a.txt").readText().trimEnd())
        assertTrue(!File(workspace, "new.txt").exists())
        assertEquals(listOf("b.txt"), source.status(null).changes().map(GitChange::id))
    }

    @Test
    fun resettingToHeadDiscardsTrackedChangesAndKeepsUntrackedFiles(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        repositories.write("demo", "a.txt", "a2\n")
        repositories.write("demo", "new.txt", "new\n")
        val source = repositories.source("demo")

        val result = source.runProjectAction("demo", GitProjectAction.RESET_HEAD)

        assertEquals(GitOutcome.SUCCESS, result.outcome)
        assertEquals(listOf("new.txt"), source.status(null).changes().map(GitChange::id))
    }

    @Test
    fun aCommitWithoutAnAuthorIsRefused(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        repositories.write("demo", "a.txt", "a2\n")
        repositories.identity = null
        val source = repositories.source("demo")

        assertFailsWith<GitFault.IdentityMissing> {
            source.commit("demo", GitCommitSelection(listOf("a.txt"), 1), "No author")
        }
    }

    @Test
    fun aDiffCarriesBothSidesAndTheLineCountsOfItsHunks(): Unit = runBlocking {
        val original = (1..10).joinToString("\n", postfix = "\n") { "line $it" }
        repositories.initWithCommit("demo", mapOf("a.txt" to original)).close()
        repositories.write("demo", "a.txt", original.replace("line 5\n", "line five\nline 5b\n"))

        val diff = repositories.source("demo").diff("demo", "a.txt")

        assertEquals(original, diff.beforeContent)
        assertEquals(2, diff.addedLines)
        assertEquals(1, diff.deletedLines)
        assertEquals(false, diff.tooLarge)
    }

    @Test
    fun aDiffOfAFileAboveTheLineCapIsTooLarge(): Unit = runBlocking {
        val huge = (1..2_001).joinToString("\n", postfix = "\n") { "line $it" }
        repositories.initWithCommit("demo", mapOf("big.txt" to "small\n")).close()
        repositories.write("demo", "big.txt", huge)

        val diff = repositories.source("demo").diff("demo", "big.txt")

        assertTrue(diff.tooLarge)
        assertNull(diff.afterContent)
    }

    @Test
    fun historyPagesAcrossTwoPagesAndACommitShowsItsFileDiff(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "1\n")).use { git ->
            listOf("2", "3").forEach { value ->
                repositories.write("demo", "a.txt", "$value\n")
                git.commit().setAll(true).setMessage("Set $value").setAuthor("Seed", "s@example.com")
                    .setCommitter("Seed", "s@example.com").call()
            }
        }
        val source = repositories.source("demo")

        val first = source.history("demo", cursor = null, limit = 2)
        val second = source.history("demo", cursor = first.nextCursor, limit = 2)

        assertEquals(listOf("Set 3", "Set 2"), first.commits.map { it.subject })
        assertEquals(listOf("Initial"), second.commits.map { it.subject })
        assertNull(second.nextCursor)
        val detail = source.commitDetail("demo", first.commits.first().id)
        assertEquals(listOf("a.txt"), detail.files.map { it.id })
        val fileDiff = source.commitFileDiff("demo", detail.commit.id, "a.txt")
        assertEquals("2\n", fileDiff.beforeContent)
        assertEquals("3\n", fileDiff.afterContent)
    }

    @Test
    fun aCreatedBranchCanBeCheckedOut(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        val source = repositories.source("demo")

        source.createBranch("demo", "feature/login")
        val branches = source.branches("demo")
        val feature = branches.localBranches.single { it.displayName == "feature/login" }
        source.checkout("demo", GitCheckoutTarget(feature.id, branches.revision))

        assertEquals("feature/login", source.status(null).repositories.single().currentBranch)
    }

    @Test
    fun aLocalBranchCanBeDeletedButNotTheCurrentOne(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        val source = repositories.source("demo")
        source.createBranch("demo", "feature/login")
        val branches = source.branches("demo")
        val feature = branches.localBranches.single { it.displayName == "feature/login" }
        val main = branches.localBranches.single { it.isCurrent }

        assertEquals(setOf(GitAction.CHECKOUT, GitAction.DELETE), feature.availableActions)
        assertEquals(
            GitRejection.REJECTED,
            assertFailsWith<GitFault.Rejected> { source.deleteBranch("demo", main.id, branches.revision) }.reason,
        )
        assertEquals(
            GitRejection.STALE_TARGET,
            assertFailsWith<GitFault.Rejected> { source.deleteBranch("demo", feature.id, branches.revision + 1) }.reason,
        )
        source.deleteBranch("demo", feature.id, branches.revision)

        assertEquals(listOf(main.id), source.branches("demo").localBranches.map { it.id })
    }

    @Test
    fun checkoutIsRefusedWhenLocalChangesWouldBeOverwritten(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).use { git ->
            git.checkout().setCreateBranch(true).setName("other").call()
            repositories.write("demo", "a.txt", "other\n")
            git.commit().setAll(true).setMessage("Other").setAuthor("Seed", "s@example.com")
                .setCommitter("Seed", "s@example.com").call()
            git.checkout().setName(GitTestRepositories.MAIN).call()
        }
        repositories.write("demo", "a.txt", "uncommitted\n")
        val source = repositories.source("demo")

        val refusal = assertFailsWith<GitFault.Rejected> {
            source.checkout("demo", GitCheckoutTarget(Constants.R_HEADS + "other", 0))
        }

        assertEquals(GitRejection.REJECTED, refusal.reason)
        assertEquals("uncommitted\n", File(repositories.workspace("demo"), "a.txt").readText())
    }

    @Test
    fun pushesToABareRemoteAndRefusesToOverwriteItsNewerCommits(): Unit = runBlocking {
        val remote = repositories.bareRemote(mapOf("a.txt" to "a\n"))
        repositories.executor.execute(GitTransfer.Clone("demo", remote), NullProgressMonitor.INSTANCE)
        val source = repositories.source("demo")
        commitLocally(source, "a.txt", "mine\n")

        assertEquals(GitOutcome.SUCCESS, source.push("demo", "", 0).outcome)

        repositories.commitOnRemote(remote, "a.txt", "theirs\n")
        commitLocally(source, "b.txt", "mine too\n")
        assertFailsWith<GitFault.NonFastForward> { source.push("demo", "", 0) }
    }

    @Test
    fun pullFastForwardsAndADivergedPullAsksForAMergeThatCanConflict(): Unit = runBlocking {
        val remote = repositories.bareRemote(mapOf("a.txt" to "a\n"))
        repositories.executor.execute(GitTransfer.Clone("demo", remote), NullProgressMonitor.INSTANCE)
        val source = repositories.source("demo")

        repositories.commitOnRemote(remote, "b.txt", "from remote\n")
        assertEquals(GitOutcome.SUCCESS, source.pull("demo").outcome)
        // A Windows host may check text out with CRLF line endings.
        assertEquals("from remote\n", File(repositories.workspace("demo"), "b.txt").readText().replace("\r\n", "\n"))

        repositories.commitOnRemote(remote, "a.txt", "remote edit\n")
        commitLocally(source, "a.txt", "local edit\n")
        assertFailsWith<GitFault.NonFastForward> { source.pull("demo") }

        val conflicts = assertFailsWith<GitFault.Conflicts> { source.merge("demo") }
        assertEquals(listOf("a.txt"), conflicts.paths)
        assertEquals(GitChangeKind.CONFLICTED, source.status(null).changes().single { it.id == "a.txt" }.kind)
    }

    @Test
    fun aDirectoryWithoutARepositoryIsNotARepository(): Unit = runBlocking {
        repositories.workspace("plain")

        assertFailsWith<GitFault.NotARepository> { repositories.source("plain").status(null) }
    }

    private suspend fun commitLocally(source: JGitRepositorySource, path: String, text: String) {
        repositories.write("demo", path, text)
        val status = source.status(null)
        assertNotNull(status.changes().singleOrNull { it.id == path })
        source.commit("demo", GitCommitSelection(listOf(path), status.revision), "Edit $path")
    }

    private fun GitStatus.changes(): List<GitChange> = repositories.single().changeLists.flatMap { it.changes }

    private companion object {
        val RENAMED_CONTENT = (1..20).joinToString("\n", postfix = "\n") { "stable line $it" }
    }
}
