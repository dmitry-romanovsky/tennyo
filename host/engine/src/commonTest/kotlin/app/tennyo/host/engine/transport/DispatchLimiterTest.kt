package app.tennyo.host.engine.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchLimiterTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val limiter = DispatchLimiter(scope)
    private val release = CompletableDeferred<Unit>()
    private val started = mutableListOf<Int>()

    private fun blockingTask(id: Int): suspend () -> Unit = {
        started += id
        release.await()
    }

    @Test
    fun `runs four at once and queues the rest in order`() {
        repeat(6) { assertTrue(limiter.trySubmit(blockingTask(it))) }
        scope.runCurrent()

        assertEquals(listOf(0, 1, 2, 3), started)
        assertEquals(DispatchLimiter.Snapshot(running = 4, queued = 2), limiter.snapshot())

        release.complete(Unit)
        scope.runCurrent()

        assertEquals(listOf(0, 1, 2, 3, 4, 5), started)
        assertEquals(DispatchLimiter.Snapshot(running = 0, queued = 0), limiter.snapshot())
    }

    @Test
    fun `refuses a request once the queue is full`() {
        val capacity = DispatchLimiter.MAX_CONCURRENT + DispatchLimiter.MAX_QUEUED
        repeat(capacity) { assertTrue(limiter.trySubmit(blockingTask(it))) }

        assertFalse(limiter.trySubmit(blockingTask(capacity)))

        scope.runCurrent()
        assertEquals(
            DispatchLimiter.Snapshot(
                running = DispatchLimiter.MAX_CONCURRENT,
                queued = DispatchLimiter.MAX_QUEUED,
            ),
            limiter.snapshot(),
        )
        release.complete(Unit)
        scope.runCurrent()
        assertEquals(capacity, started.size)
        assertTrue(limiter.trySubmit(blockingTask(capacity)))
    }

    @Test
    fun `a failing request frees its slot for the next one`() {
        val failures = mutableListOf<Throwable>()
        val supervised = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scope.testScheduler) +
                    CoroutineExceptionHandler { _, failure -> failures += failure },
        )
        val limiter = DispatchLimiter(supervised, maxConcurrent = 1, maxQueued = 1)

        assertTrue(limiter.trySubmit { error("route failed") })
        assertTrue(limiter.trySubmit { started += 1 })
        scope.runCurrent()

        assertEquals(listOf("route failed"), failures.map { it.message })
        assertEquals(listOf(1), started)
        assertEquals(DispatchLimiter.Snapshot(running = 0, queued = 0), limiter.snapshot())
    }
}
