package app.tennyo.host.engine.session

import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

/**
 * Preserves authenticated call order while bounding the work retained for one channel. [schedule] runs the drain
 * off the transport thread; calls themselves always run one at a time, in submission order.
 */
class OrderedCallQueue(
    private val schedule: (drain: () -> Unit) -> Unit,
    private val maxPendingCommands: Int,
) {
    private val monitor = HostMonitor()
    private val pending = ArrayDeque<() -> Unit>()
    private var drainScheduled = false
    private var closed = false

    init {
        require(maxPendingCommands > 0)
    }

    fun submit(command: () -> Unit): Boolean {
        val scheduleDrain = monitor.guarded {
            if (closed || pending.size >= maxPendingCommands) return false
            pending.addLast(command)
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (scheduleDrain) schedule(::drain)
        return true
    }

    fun close() = monitor.guarded {
        closed = true
        pending.clear()
    }

    private fun drain() {
        while (true) {
            val command: (() -> Unit)? = monitor.guarded {
                if (closed) {
                    drainScheduled = false
                    null
                } else {
                    pending.removeFirstOrNull().also {
                        if (it == null) drainScheduled = false
                    }
                }
            }
            command ?: return
            runCatching(command).onFailure { LOG.warn("Tennyo ordered call failed", it) }
        }
    }

    private companion object {
        val LOG = HostLogger("OrderedCallQueue")
    }
}
