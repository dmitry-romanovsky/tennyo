package app.tennyo.mobile.link.session

/**
 * Whether the session is about to enter, or is in, background-idle mode. While it is, connection failures are expected
 * (the stream was detached on purpose) and must not read as a lost connection.
 */
internal class BackgroundIdleState {
    var requested: Boolean = false
        private set
    var active: Boolean = false
        private set
    private var syncDetached: Boolean = false

    val shouldSuppressConnectionLoss: Boolean
        get() = requested || active

    fun request() {
        requested = true
    }

    fun enter() {
        requested = false
        active = true
        syncDetached = true
    }

    fun markSyncDetachedForBackground(): Boolean {
        if (!shouldSuppressConnectionLoss) return false
        syncDetached = true
        return true
    }

    fun cancel(): Boolean {
        val shouldResume = active || syncDetached
        clear()
        return shouldResume
    }

    fun clear() {
        requested = false
        active = false
        syncDetached = false
    }
}
