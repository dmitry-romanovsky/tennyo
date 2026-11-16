package app.tennyo.host.engine.platform

/** Node runs the core on a single thread; there is nothing to exclude. */
actual class HostMonitor actual constructor() {
    actual fun lock() = Unit

    actual fun unlock() = Unit
}
