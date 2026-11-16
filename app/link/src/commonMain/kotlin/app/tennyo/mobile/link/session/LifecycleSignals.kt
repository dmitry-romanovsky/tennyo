package app.tennyo.mobile.link.session

import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.SessionStats

/**
 * The calls the session parts make back into the lifecycle — reporting health, loss and failure, and asking for a
 * (re)connect. Parts depend on this instead of on each other, so their constructors do not form a cycle.
 */
internal interface LifecycleSignals {
    fun markConnectionHealthy(lastPingMillis: Long? = null, stats: SessionStats? = null)

    /** The connection dropped; [retrying] says whether a reconnect is under way or reconnecting gave up. */
    fun markConnectionLost(retrying: Boolean, throwable: Throwable)

    fun scheduleReconnect(throwable: Throwable)

    fun handleConnectionFailure(throwable: Throwable)

    fun startConnectionTimeout()

    fun clearConnectionTimeout()

    fun restartPairingConfirmationTimeout(challenge: PairingChallenge)

    fun showPairingStoppedAfterTooManyAttempts()

    fun showPairingWordsExpired()

    fun connect(resume: Boolean)
}

/**
 * Handed to the parts before the ones it forwards to exist, then [wire]d exactly once when the session finishes
 * building. A signal raised before that fails with a message naming the ordering bug instead of a bare
 * NullPointerException from a half-built object.
 */
internal class LateBoundLifecycleSignals : LifecycleSignals {
    private var target: LifecycleSignals? = null

    fun wire(target: LifecycleSignals) {
        check(this.target == null) { "Lifecycle signals are already wired." }
        this.target = target
    }

    private val wired: LifecycleSignals
        get() = checkNotNull(target) {
            "A lifecycle signal was raised before the session finished wiring its parts."
        }

    override fun markConnectionHealthy(lastPingMillis: Long?, stats: SessionStats?) =
        wired.markConnectionHealthy(lastPingMillis, stats)

    override fun markConnectionLost(retrying: Boolean, throwable: Throwable) =
        wired.markConnectionLost(retrying, throwable)

    override fun scheduleReconnect(throwable: Throwable) = wired.scheduleReconnect(throwable)

    override fun handleConnectionFailure(throwable: Throwable) = wired.handleConnectionFailure(throwable)

    override fun startConnectionTimeout() = wired.startConnectionTimeout()

    override fun clearConnectionTimeout() = wired.clearConnectionTimeout()

    override fun restartPairingConfirmationTimeout(challenge: PairingChallenge) =
        wired.restartPairingConfirmationTimeout(challenge)

    override fun showPairingStoppedAfterTooManyAttempts() = wired.showPairingStoppedAfterTooManyAttempts()

    override fun showPairingWordsExpired() = wired.showPairingWordsExpired()

    override fun connect(resume: Boolean) = wired.connect(resume)
}
