package app.tennyo.host.engine.git

import app.tennyo.contract.GitAction
import app.tennyo.contract.GitChange
import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitCheckoutRequest
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitProjectAction
import app.tennyo.contract.GitRemote
import app.tennyo.contract.GitRepositorySummary
import app.tennyo.contract.GitRollbackRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The CLI port against real repositories made with the `git` on this machine. */
class CliGitPortTest {
    private val root: File = Files.createTempDirectory("tennyo-git").toFile()
    private val port = CliGitPort(JvmGitProcesses, { listOf(root.path) })

    init {
        git("init", "--quiet", "--initial-branch=main")
        git("config", "user.name", "Ada")
        git("config", "user.email", "ada@example.test")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
        write("a.txt", "one\n")
        write("b.txt", "two\n")
        git("add", ".")
        git("commit", "--quiet", "-m", "Initial")
    }

    @AfterTest
    fun deleteRepository() {
        root.deleteRecursively()
    }

    @Test
    fun `status lists tracked and untracked changes with line counts`() = runBlocking {
        write("a.txt", "one\nmore\n")
        write("dir/new file.txt", "new\n")

        val repository = repository()

        assertEquals("main", repository.currentBranch)
        assertEquals(
            listOf(GitChangeKind.MODIFIED to "a.txt", GitChangeKind.UNTRACKED to "dir/new file.txt"),
            repository.changes().map { it.kind to (it.afterPath ?: it.beforePath) },
        )
        assertEquals(1, repository.change("a.txt").addedLines)
    }

    @Test
    fun `a repository below the project reports where it lives`() = runBlocking {
        val nested = root.resolve("nested").apply { mkdirs() }
        git("-C", nested.path, "init", "--quiet")
        val nestedPort = CliGitPort(JvmGitProcesses, { listOf(root.invariantSeparatorsPath, nested.invariantSeparatorsPath) })

        val locations = nestedPort.status(null, 0)!!.repositories.associate { it.rootName to it.projectPath }

        assertEquals(mapOf(root.name to "", "nested" to "nested"), locations)
    }

    @Test
    fun `commit takes only the chosen changes, untracked files included`() = runBlocking {
        write("a.txt", "changed\n")
        write("b.txt", "also changed\n")
        write("c.txt", "new\n")
        val repository = repository()

        val result = port.commit(
            GitCommitRequest(repository.id, listOf(repository.change("a.txt").id, repository.change("c.txt").id), "Pick", 0),
            revision = 0,
        )

        assertEquals(GitOperationOutcome.SUCCESS, result.outcome)
        assertEquals(listOf("b.txt"), repository().changes().map { it.afterPath })
        assertEquals("Pick", git("log", "-1", "--format=%s").trim())
    }

    @Test
    fun `a failed commit leaves untracked files untracked`() = runBlocking {
        git("config", "core.hooksPath", root.resolve("hooks").path)
        write("hooks/pre-commit", "#!/bin/sh\nexit 1\n")
        root.resolve("hooks/pre-commit").setExecutable(true)
        write("c.txt", "new\n")
        val repository = repository()

        val result = port.commit(GitCommitRequest(repository.id, listOf(repository.change("c.txt").id), "No", 0), 0)

        assertEquals(GitOperationOutcome.REJECTED, result.outcome)
        assertEquals(GitChangeKind.UNTRACKED, repository().change("c.txt").kind)
    }

    @Test
    fun `rollback restores tracked files and deletes untracked ones, leaving the rest`() = runBlocking {
        write("a.txt", "changed\n")
        write("b.txt", "kept\n")
        write("c.txt", "new\n")
        val repository = repository()
        val chosen = listOf(repository.change("a.txt").id, repository.change("c.txt").id)

        val result = port.rollback(GitRollbackRequest(repository.id, chosen, 0), revision = 0)

        assertEquals(GitOperationOutcome.SUCCESS, result.outcome)
        assertEquals("one\n", read("a.txt"))
        assertFalse(root.resolve("c.txt").exists())
        assertEquals(listOf("b.txt"), repository().changes().map { it.afterPath })
    }

    @Test
    fun `rollback of a staged new file removes it`() = runBlocking {
        write("staged.txt", "new\n")
        git("add", "staged.txt")
        val repository = repository()

        val result = port.rollback(GitRollbackRequest(repository.id, listOf(repository.change("staged.txt").id), 0), 0)

        assertEquals(GitOperationOutcome.SUCCESS, result.outcome)
        assertTrue(repository().changes().isEmpty())
    }

    @Test
    fun `rollback refuses a stale revision or a change that is gone`() = runBlocking {
        write("a.txt", "changed\n")
        val repository = repository()
        val id = repository.change("a.txt").id

        assertEquals(GitOperationOutcome.STALE_TARGET, port.rollback(GitRollbackRequest(repository.id, listOf(id), 1), 2).outcome)
        assertEquals(GitOperationOutcome.STALE_TARGET, port.rollback(GitRollbackRequest(repository.id, listOf("gone"), 0), 0).outcome)
        assertEquals("changed\n", read("a.txt"))
    }

    @Test
    fun `reset to HEAD discards tracked changes and keeps untracked files`() = runBlocking {
        write("a.txt", "changed\n")
        write("c.txt", "new\n")

        val result = port.runProjectAction(repository().id, GitProjectAction.RESET_HEAD)

        assertEquals(GitOperationOutcome.SUCCESS, result.outcome)
        assertEquals("one\n", read("a.txt"))
        assertTrue(root.resolve("c.txt").exists())
    }

    @Test
    fun `stash puts changes aside and applying brings them back, nothing to stash is refused`() = runBlocking {
        val id = repository().id
        assertEquals(GitOperationOutcome.REJECTED, port.runProjectAction(id, GitProjectAction.STASH).outcome)
        write("a.txt", "changed\n")

        assertEquals(GitOperationOutcome.SUCCESS, port.runProjectAction(id, GitProjectAction.STASH).outcome)
        assertEquals("one\n", read("a.txt"))
        assertEquals(1, port.projectInfo(id)?.stashCount)
        assertEquals(GitOperationOutcome.SUCCESS, port.runProjectAction(id, GitProjectAction.APPLY_STASH).outcome)
        assertEquals("changed\n", read("a.txt"))
    }

    @Test
    fun `a new branch can be checked out by its id`() = runBlocking {
        val id = repository().id
        assertEquals(GitOperationOutcome.SUCCESS, port.createBranch(id, "feature/x").outcome)
        assertEquals(GitOperationOutcome.REJECTED, port.createBranch(id, "-bad").outcome)
        val branch = port.branches(id, 0)!!.localBranches.first { it.fullName == "feature/x" }

        assertEquals(GitOperationOutcome.SUCCESS, port.checkout(GitCheckoutRequest(id, branch.id, 0), 0).outcome)
        assertEquals("feature/x", repository().currentBranch)
    }

    @Test
    fun `a merged and an unmerged local branch are deleted, the current one is refused`() = runBlocking {
        val id = repository().id
        git("branch", "merged")
        git("switch", "--quiet", "-c", "unmerged")
        write("a.txt", "only here\n")
        git("commit", "--quiet", "-am", "Only here")
        git("switch", "--quiet", "main")
        val branches = port.branches(id, 0)!!.localBranches.associateBy { it.fullName }

        assertEquals(setOf(GitAction.CHECKOUT, GitAction.DELETE), branches.getValue("merged").availableActions)
        assertTrue(branches.getValue("main").availableActions.isEmpty())
        assertEquals(GitOperationOutcome.REJECTED, port.deleteBranch(id, branches.getValue("main").id, 0, 0).outcome)
        assertEquals(GitOperationOutcome.SUCCESS, port.deleteBranch(id, branches.getValue("merged").id, 0, 0).outcome)
        assertEquals(GitOperationOutcome.SUCCESS, port.deleteBranch(id, branches.getValue("unmerged").id, 0, 0).outcome)
        assertEquals(listOf("main"), port.branches(id, 0)!!.localBranches.map { it.fullName })
    }

    @Test
    fun `deleting a branch refuses a stale revision or a branch that is gone`() = runBlocking {
        val id = repository().id
        git("branch", "feature")
        val feature = port.branches(id, 2)!!.localBranches.first { it.fullName == "feature" }

        assertEquals(GitOperationOutcome.STALE_TARGET, port.deleteBranch(id, feature.id, 1, 2).outcome)
        assertEquals(GitOperationOutcome.STALE_TARGET, port.deleteBranch(id, "gone", 2, 2).outcome)
        assertTrue(git("branch", "--list", "feature").isNotBlank())
    }

    @Test
    fun `remotes are saved, shown without credentials and kept when sent back unchanged`() = runBlocking {
        val id = repository().id
        port.saveRemote(id, null, GitRemote("origin", "https://ada:secret@example.test/app.git"))
        val shown = port.projectInfo(id)!!.remotes.single()

        assertEquals(GitRemote("origin", "https://example.test/app.git"), shown)
        assertEquals(GitOperationOutcome.SUCCESS, port.saveRemote(id, "origin", shown.copy(name = "upstream")).outcome)
        assertEquals("https://ada:secret@example.test/app.git", git("remote", "get-url", "upstream").trim())
        assertEquals(GitOperationOutcome.REJECTED, port.saveRemote(id, null, GitRemote("--upload-pack", "x")).outcome)
    }

    @Test
    fun `history and commit detail read what was committed`() = runBlocking {
        val id = repository().id
        val commit = (port.history(id, 0, 0, 10) as app.tennyo.host.engine.panes.GitHistoryResult.Success)
            .response.commits.single()
        val detail = port.commitDetail(id, 0, commit.id) as app.tennyo.host.engine.panes.GitCommitDetailResult.Success
        val file = detail.response.files.first { it.afterPath == "a.txt" }

        assertEquals("Initial", detail.response.commit.subject)
        assertEquals("main", commit.refs.single().displayName)
        assertEquals("one\n", port.commitDiff(id, commit.id, file.id)?.afterContent)
    }

    @Test
    fun `cancelling the task in the editor answers cancelled`() = runBlocking {
        write("a.txt", "changed\n")
        val cancelling = object : GitTaskReporter {
            override suspend fun <T> run(task: GitTask, repositoryName: String, block: suspend () -> T): T =
                coroutineScope {
                    val work = async { block() }
                    work.cancel()
                    work.await()
                }
        }
        val port = CliGitPort(JvmGitProcesses, { listOf(root.path) }, cancelling)

        assertEquals(GitOperationOutcome.CANCELLED, port.runProjectAction(repository().id, GitProjectAction.STASH).outcome)
    }

    @Test
    fun `a git that hangs is killed when its caller gives up`() = runBlocking {
        val elapsed = measureTime {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) {
                    JvmGitProcesses.run(root.path, listOf("-c", "alias.hang=!sleep 30", "hang"), emptyMap(), null, 1_000)
                }
            }
        }

        assertTrue(elapsed < 10.seconds, "took $elapsed")
    }

    @Test
    fun `a symlink diffs as its target path, never as the file it points to`() = runBlocking {
        val outside = Files.createTempFile("tennyo-secret", ".txt").toFile().apply { writeText("secret\n") }
        try {
            val link = try {
                Files.createSymbolicLink(root.resolve("leak").toPath(), outside.toPath())
            } catch (_: Exception) {
                return@runBlocking // Windows without Developer Mode cannot create symlinks.
            }
            val repository = repository()

            val diff = port.diff(repository.id, repository.change("leak").id)!!

            assertEquals(Files.readSymbolicLink(link).toString(), diff.afterContent)
        } finally {
            outside.delete()
        }
    }

    private suspend fun repository(): GitRepositorySummary =port.status(null, 0)!!.repositories.single()

    private fun GitRepositorySummary.changes(): List<GitChange> = changeLists.flatMap { it.changes }

    private fun GitRepositorySummary.change(path: String): GitChange =
        changes().first { (it.afterPath ?: it.beforePath) == path }

    private fun write(path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(path: String): String = root.resolve(path).readText()

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}
