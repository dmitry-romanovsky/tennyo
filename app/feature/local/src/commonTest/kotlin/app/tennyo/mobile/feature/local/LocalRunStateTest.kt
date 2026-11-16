package app.tennyo.mobile.feature.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRunStateTest {
    private val output = PythonRun("Hello, world!", PythonRunOutcome.SUCCEEDED)

    @Test
    fun `a run shows progress then what it printed`() {
        val running = reduce(LocalWorkspaceState("demo"), LocalWorkspaceMsg.RunStarted("main.py"))
        assertTrue(running.run!!.running)

        val finished = reduce(running, LocalWorkspaceMsg.RunFinished("main.py", output))

        assertFalse(finished.run!!.running)
        assertEquals(output, finished.run!!.result)
    }

    @Test
    fun `a result for a run the user dismissed is dropped`() {
        val dismissed = reduce(
            reduce(LocalWorkspaceState("demo"), LocalWorkspaceMsg.RunStarted("main.py")),
            LocalWorkspaceMsg.RunDismissed,
        )

        assertNull(reduce(dismissed, LocalWorkspaceMsg.RunFinished("main.py", output)).run)
    }

    @Test
    fun `only Python files run`() {
        assertTrue(isPythonScript("pkg/main.py"))
        assertFalse(isPythonScript("README.md"))
    }
}
