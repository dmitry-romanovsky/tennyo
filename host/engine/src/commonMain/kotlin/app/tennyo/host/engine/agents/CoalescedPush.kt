package app.tennyo.host.engine.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pushes a chat backend's state at most once per [delayMillis] without losing the last change. The flag clears before
 * [push] reads the state, so a change that lands while a push is on its way schedules the next one instead of waiting
 * for some later change.
 */
internal class CoalescedPush(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val push: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    fun schedule() {
        if (!scheduled.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            delay(delayMillis.milliseconds)
            scheduled.store(false)
            push()
        }
    }
}
