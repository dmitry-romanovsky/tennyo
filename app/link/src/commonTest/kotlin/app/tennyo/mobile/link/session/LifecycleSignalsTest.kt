package app.tennyo.mobile.link.session

import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.SessionStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleSignalsTest {

    @Test
    fun aSignalBeforeWiringNamesTheOrderingBugInsteadOfThrowingNpe() {
        val signals = LateBoundLifecycleSignals()

        val failure = assertFailsWith<IllegalStateException> { signals.clearConnectionTimeout() }

        assertTrue("before the session finished wiring" in failure.message.orEmpty())
    }

    @Test
    fun wiredSignalsForwardEveryCall() {
        val signals = LateBoundLifecycleSignals()
        val recorder = RecordingSignals()
        signals.wire(recorder)

        signals.markConnectionHealthy()
        signals.scheduleReconnect(RuntimeException())
        signals.handleConnectionFailure(RuntimeException())
        signals.startConnectionTimeout()
        signals.clearConnectionTimeout()
        signals.showPairingStoppedAfterTooManyAttempts()
        signals.showPairingWordsExpired()
        signals.connect(resume = true)

        assertEquals(
            listOf(
                "healthy", "reconnect", "failure", "startTimeout", "clearTimeout",
                "pairingStopped", "wordsExpired", "connect:true",
            ),
            recorder.calls,
        )
    }

    @Test
    fun wiringTwiceIsRejected() {
        val signals = LateBoundLifecycleSignals()
        signals.wire(RecordingSignals())

        assertFailsWith<IllegalStateException> { signals.wire(RecordingSignals()) }
    }

    private class RecordingSignals : LifecycleSignals {
        val calls = mutableListOf<String>()

        override fun markConnectionHealthy(lastPingMillis: Long?, stats: SessionStats?) {
            calls += "healthy"
        }

        override fun markConnectionLost(retrying: Boolean, throwable: Throwable) {
            calls += "lost:$retrying"
        }

        override fun scheduleReconnect(throwable: Throwable) {
            calls += "reconnect"
        }

        override fun handleConnectionFailure(throwable: Throwable) {
            calls += "failure"
        }

        override fun startConnectionTimeout() {
            calls += "startTimeout"
        }

        override fun clearConnectionTimeout() {
            calls += "clearTimeout"
        }

        override fun restartPairingConfirmationTimeout(challenge: PairingChallenge) {
            calls += "restartPairing"
        }

        override fun showPairingStoppedAfterTooManyAttempts() {
            calls += "pairingStopped"
        }

        override fun showPairingWordsExpired() {
            calls += "wordsExpired"
        }

        override fun connect(resume: Boolean) {
            calls += "connect:$resume"
        }
    }
}
