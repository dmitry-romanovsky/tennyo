package app.tennyo.host.engine.panes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LaunchOutputAccumulatorTest {
    @Test
    fun `chunk burst becomes one ordered output event`() {
        val accumulator = LaunchOutputAccumulator(characterLimit = 80_000)
        repeat(100) { accumulator.append("tab", "config", "$it,", isRunning = true) }

        val output = accumulator.drain().single()

        assertEquals((0 until 100).joinToString(separator = "") { "$it," }, output.text)
        assertEquals("tab", output.tabId)
        assertTrue(output.isRunning)
    }

    @Test
    fun `tab flush leaves other tab buffered and keeps final state`() {
        val accumulator = LaunchOutputAccumulator(characterLimit = 80_000)
        accumulator.append("first", "config-1", "running", isRunning = true)
        accumulator.append("first", "config-1", " done", isRunning = false)
        accumulator.append("second", "config-2", "held", isRunning = true)

        val output = accumulator.drain("first").single()

        assertEquals("running done", output.text)
        assertEquals(false, output.isRunning)
        assertEquals("second", accumulator.drain().single().tabId)
        assertTrue(accumulator.isEmpty())
    }

    @Test
    fun `clear cancels all pending delivery state`() {
        val accumulator = LaunchOutputAccumulator(characterLimit = 80_000)
        accumulator.append("tab", "config", "pending", isRunning = true)
        accumulator.clear()
        assertTrue(accumulator.drain().isEmpty())
    }

    @Test
    fun `existing console output seeds an empty session buffer`() {
        val buffer = LaunchOutputBuffer(characterLimit = 12)

        buffer.seedIfEmpty("existing console output")

        assertEquals("nsole output", buffer.snapshot())
    }

    @Test
    fun `existing console output never overwrites captured live output`() {
        val buffer = LaunchOutputBuffer(characterLimit = 80_000)
        buffer.append("captured live output")

        buffer.seedIfEmpty("older console snapshot")

        assertEquals("captured live output", buffer.snapshot())
    }

    @Test
    fun `pending output stays bounded while preserving its newest text`() {
        val accumulator = LaunchOutputAccumulator(characterLimit = 12)

        repeat(100) { accumulator.append("tab", "config", "$it,", isRunning = true) }

        assertEquals(
            (0 until 100).joinToString(separator = "") { "$it," }.takeLast(12),
            accumulator.drain().single().text
        )
    }
}
