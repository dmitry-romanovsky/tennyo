package app.tennyo.host.engine.agents

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescedPushTest {
    @Test
    fun `changes inside one window are pushed once`() = runTest {
        var pushes = 0
        val push = CoalescedPush(backgroundScope, DELAY_MILLIS) { pushes++ }

        repeat(3) { push.schedule() }
        advanceTimeBy(DELAY_MILLIS + 1)
        runCurrent()

        assertEquals(1, pushes)
    }

    @Test
    fun `a change that lands while a push is sent is pushed as well`() = runTest {
        var pushes = 0
        lateinit var push: CoalescedPush
        push = CoalescedPush(backgroundScope, DELAY_MILLIS) {
            pushes++
            if (pushes == 1) push.schedule()
        }

        push.schedule()
        advanceTimeBy(2 * DELAY_MILLIS + 1)
        runCurrent()

        assertEquals(2, pushes)
    }

    private companion object {
        const val DELAY_MILLIS = 100L
    }
}
