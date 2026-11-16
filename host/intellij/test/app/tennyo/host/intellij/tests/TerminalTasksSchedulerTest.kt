package app.tennyo.host.intellij.tests

import app.tennyo.host.intellij.TerminalTasksScheduler
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class TerminalTasksSchedulerTest {
    @Test
    fun `rejects tasks after parent disposal`() {
        val parentDisposable = Disposer.newDisposable()
        val scheduler = TerminalTasksScheduler(parentDisposable)

        Disposer.dispose(parentDisposable)

        assertFalse(scheduler.schedule(delayMillis = 1) { error("Disposed scheduler must not run new tasks") })
    }

    @Test
    fun `accepts tasks while live`() {
        val parentDisposable = Disposer.newDisposable()
        try {
            assertTrue(TerminalTasksScheduler(parentDisposable).schedule(delayMillis = 1) {})
        } finally {
            Disposer.dispose(parentDisposable)
        }
    }

    @Test
    fun `runs task after its delay`() {
        val parentDisposable = Disposer.newDisposable()
        try {
            val ran = CountDownLatch(1)

            TerminalTasksScheduler(parentDisposable).schedule(delayMillis = 10) { ran.countDown() }

            assertTrue(ran.await(5, TimeUnit.SECONDS))
        } finally {
            Disposer.dispose(parentDisposable)
        }
    }

    @Test
    fun `disposal cancels pending task`() {
        val parentDisposable = Disposer.newDisposable()
        val ran = CountDownLatch(1)
        TerminalTasksScheduler(parentDisposable).schedule(delayMillis = 300) { ran.countDown() }

        Disposer.dispose(parentDisposable)

        assertFalse(ran.await(600, TimeUnit.MILLISECONDS))
    }
}
