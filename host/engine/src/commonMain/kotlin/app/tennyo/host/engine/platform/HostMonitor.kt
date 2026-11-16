package app.tennyo.host.engine.platform

/**
 * Mutual exclusion for protocol state that IDE threads touch concurrently. On the JVM it is a reentrant lock; Node runs
 * the core on one thread, so there it guards nothing.
 */
expect class HostMonitor() {
    fun lock()

    fun unlock()
}

inline fun <T> HostMonitor.guarded(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
