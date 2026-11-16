package app.tennyo.host.engine.transport

import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bounds route dispatch: at most [maxConcurrent] requests run at once and at most [maxQueued] wait for a slot. A
 * request beyond that is refused so the listener can answer `503 server_busy` instead of queueing without limit.
 *
 * Tasks run in [scope]; a listener whose routes block passes a scope on a dispatcher that may block.
 */
class DispatchLimiter(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val maxQueued: Int = MAX_QUEUED,
) {
    private val monitor = HostMonitor()
    private val queued = ArrayDeque<suspend () -> Unit>()
    private var running = 0

    /** Starts or queues [task]. Returns `false`, without keeping [task], when every slot and queue place is taken. */
    fun trySubmit(task: suspend () -> Unit): Boolean {
        val startNow = monitor.guarded {
            when {
                running < maxConcurrent -> {
                    running++
                    true
                }

                queued.size < maxQueued -> {
                    queued.addLast(task)
                    false
                }

                else -> return false
            }
        }
        if (startNow) start(task)
        return true
    }

    /** Requests running or waiting, for diagnostics and tests. */
    fun snapshot(): Snapshot = monitor.guarded { Snapshot(running = running, queued = queued.size) }

    private fun start(task: suspend () -> Unit) {
        scope.launch {
            try {
                task()
            } finally {
                finished()
            }
        }
    }

    /** Hands the finished task's slot to the oldest queued task, or frees it. */
    private fun finished() {
        val next = monitor.guarded {
            queued.removeFirstOrNull().also { if (it == null) running-- }
        }
        if (next != null) start(next)
    }

    data class Snapshot(val running: Int, val queued: Int)

    companion object {
        const val MAX_CONCURRENT = 4
        const val MAX_QUEUED = 256
    }
}
