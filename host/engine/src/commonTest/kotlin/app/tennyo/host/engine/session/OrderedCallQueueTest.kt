package app.tennyo.host.engine.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class OrderedCallQueueTest {
    @Test
    fun `commands execute in accepted order`() {
        val executor = CapturingExecutor()
        val queue = OrderedCallQueue(executor::execute, maxPendingCommands = 3)
        val handled = mutableListOf<Int>()

        assertTrue(queue.submit { handled += 1 })
        assertTrue(queue.submit { handled += 2 })
        assertTrue(queue.submit { handled += 3 })

        executor.runNext()

        assertEquals(listOf(1, 2, 3), handled)
    }

    @Test
    fun `queue rejects commands beyond its pending bound`() {
        val executor = CapturingExecutor()
        val queue = OrderedCallQueue(executor::execute, maxPendingCommands = 2)

        assertTrue(queue.submit {})
        assertTrue(queue.submit {})
        assertFalse(queue.submit {})
    }

    @Test
    fun `closed queue rejects and discards pending commands`() {
        val executor = CapturingExecutor()
        val queue = OrderedCallQueue(executor::execute, maxPendingCommands = 2)
        var handled = false

        assertTrue(queue.submit { handled = true })
        queue.close()
        executor.runNext()

        assertFalse(handled)
        assertFalse(queue.submit {})
    }

    private class CapturingExecutor {
        private val tasks = ArrayDeque<() -> Unit>()

        fun execute(command: () -> Unit) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().invoke()
        }
    }
}
