package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitOutcome
import app.tennyo.mobile.feature.git.GitProjectAction
import app.tennyo.mobile.feature.git.GitRemote
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The Project tab over a real repository: its details, its remotes, and stash, apply and clean-up. */
class JGitProjectSettingsTest {
    private val repositories = GitTestRepositories()

    @AfterTest
    fun cleanUp() = repositories.close()

    @Test
    fun remotesAreAddedRenamedRepointedAndRemoved(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        val source = repositories.source("demo")

        source.saveRemote("demo", null, GitRemote("origin", "https://example.com/a.git"))
        source.saveRemote("demo", "origin", GitRemote("origin", "https://example.com/b.git"))
        source.saveRemote("demo", "origin", GitRemote("upstream", "https://example.com/b.git"))

        val info = source.projectInfo("demo")
        assertEquals(listOf(GitRemote("upstream", "https://example.com/b.git")), info.remotes)
        assertEquals("demo", info.location)
        assertEquals("Initial", info.head?.subject)

        source.saveRemote("demo", null, GitRemote("origin", "https://example.com/c.git"))
        assertFailsWith<GitFault.Rejected>("a name already taken is refused") {
            source.saveRemote("demo", "origin", GitRemote("upstream", "https://example.com/c.git"))
        }
        source.removeRemote("demo", "upstream")
        assertEquals(listOf("origin"), source.projectInfo("demo").remotes.map(GitRemote::name))
    }

    @Test
    fun stashedChangesComeBackAndNothingToStashIsRefused(): Unit = runBlocking {
        repositories.initWithCommit("demo", mapOf("a.txt" to "a\n")).close()
        val source = repositories.source("demo")
        assertFailsWith<GitFault.Rejected> { source.runProjectAction("demo", GitProjectAction.STASH) }

        repositories.write("demo", "a.txt", "changed\n")
        assertEquals(GitOutcome.SUCCESS, source.runProjectAction("demo", GitProjectAction.STASH).outcome)
        assertEquals("a\n", text("a.txt"))
        assertEquals(1, source.projectInfo("demo").stashCount)

        source.runProjectAction("demo", GitProjectAction.APPLY_STASH)
        assertEquals("changed\n", text("a.txt"))
        assertEquals(0, source.projectInfo("demo").stashCount)
        assertEquals(GitOutcome.SUCCESS, source.runProjectAction("demo", GitProjectAction.CLEAN_UP).outcome)
    }

    // A checkout on a Windows host may write CRLF.
    private fun text(path: String) = File(repositories.workspace("demo"), path).readText().replace("\r\n", "\n")
}
