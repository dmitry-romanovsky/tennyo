package app.tennyo.host.engine.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NodeGitProcessesTest {
    @Test
    fun runsGitAndKillsOneThatHangs() = runTest {
        // Real time: the test scheduler's virtual clock would time the hang out before the process even starts.
        withContext(Dispatchers.Default) {
            val version = NodeGitProcesses.run(".", listOf("--version"), emptyMap(), null, OUTPUT_LIMIT)
            assertTrue(version.text.startsWith("git version"), version.text)

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) {
                    NodeGitProcesses.run(".", listOf("-c", "alias.hang=!sleep 30", "hang"), emptyMap(), null, OUTPUT_LIMIT)
                }
            }
        }
    }

    @Test
    fun outputPastTheLimitIsMarkedTruncated() = runTest {
        withContext(Dispatchers.Default) {
            val help = NodeGitProcesses.run(".", listOf("help", "-a"), emptyMap(), null, outputLimit = 10)

            assertTrue(help.truncated)
        }
    }
}

private const val OUTPUT_LIMIT = 10_000
