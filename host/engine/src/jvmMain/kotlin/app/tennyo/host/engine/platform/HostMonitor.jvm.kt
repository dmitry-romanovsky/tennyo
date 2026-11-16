package app.tennyo.host.engine.platform

import java.util.concurrent.locks.ReentrantLock

actual class HostMonitor actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() = lock.lock()

    actual fun unlock() = lock.unlock()
}
