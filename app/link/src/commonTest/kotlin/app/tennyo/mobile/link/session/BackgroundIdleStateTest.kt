package app.tennyo.mobile.link.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundIdleStateTest {
    @Test
    fun pendingIdleSuppressesConnectionLossWithoutForcingResumeWhenStreamsStayedAttached() {
        val state = BackgroundIdleState()

        state.request()

        assertTrue(state.shouldSuppressConnectionLoss)
        assertFalse(state.cancel())
        assertFalse(state.shouldSuppressConnectionLoss)
    }

    @Test
    fun streamDetachDuringPendingIdleRequestsForegroundResumeWhenUserReturns() {
        val state = BackgroundIdleState()

        state.request()

        assertTrue(state.markSyncDetachedForBackground())
        assertTrue(state.cancel())
        assertFalse(state.shouldSuppressConnectionLoss)
    }

    @Test
    fun activeIdleRequestsForegroundResumeWhenUserReturns() {
        val state = BackgroundIdleState()

        state.request()
        state.enter()

        assertTrue(state.active)
        assertTrue(state.shouldSuppressConnectionLoss)
        assertTrue(state.cancel())
        assertFalse(state.active)
    }

    @Test
    fun foregroundFailuresAreNotSuppressedBeforeIdleIsRequested() {
        val state = BackgroundIdleState()

        assertFalse(state.markSyncDetachedForBackground())
        assertFalse(state.cancel())
    }
}
