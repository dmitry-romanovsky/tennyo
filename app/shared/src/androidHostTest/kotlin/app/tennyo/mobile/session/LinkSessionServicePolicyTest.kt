package app.tennyo.mobile.session

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionServicePolicyTest {
    @Test
    fun nullIntentRestartIsNotSticky() {
        val policy = LinkSessionService.startPolicyForAction(null)

        assertEquals(Service.START_NOT_STICKY, policy.startResult)
        assertFalse(policy.startsForeground)
    }

    @Test
    fun idleKeepaliveStartIsNotSticky() {
        val policy = LinkSessionService.startPolicyForAction(
            LinkSessionService.ACTION_START_IDLE_KEEPALIVE,
        )

        assertEquals(Service.START_NOT_STICKY, policy.startResult)
        assertTrue(policy.startsForeground)
    }

    @Test
    fun idleKeepaliveStopIsNotSticky() {
        val policy = LinkSessionService.startPolicyForAction(
            LinkSessionService.ACTION_STOP_IDLE_KEEPALIVE,
        )

        assertEquals(Service.START_NOT_STICKY, policy.startResult)
        assertFalse(policy.startsForeground)
    }

    @Test
    fun idleKeepaliveStopDoesNotStartAnAbsentService() {
        assertEquals(
            SessionServiceCommandDispatch.NoOp,
            LinkSessionService.commandDispatch(
                action = LinkSessionService.ACTION_STOP_IDLE_KEEPALIVE,
                sessionId = "session",
                serviceActive = false,
            ),
        )
    }

    @Test
    fun idleKeepaliveStopUsesOrdinaryDeliveryForAnActiveService() {
        assertEquals(
            SessionServiceCommandDispatch.StartService,
            LinkSessionService.commandDispatch(
                action = LinkSessionService.ACTION_STOP_IDLE_KEEPALIVE,
                sessionId = "session",
                serviceActive = true,
            ),
        )
    }

    @Test
    fun idleKeepaliveStopIgnoresAnotherSessionsCommand() {
        assertFalse(
            LinkSessionService.matchesIdleKeepAliveSession(
                activeSessionId = "active-session",
                requestedSessionId = "other-session",
            ),
        )
    }

    @Test
    fun idleKeepaliveStopUsesInMemoryIdentityWithoutReloadingSecureStorage() {
        assertFalse(
            LinkSessionService.shouldReloadArtifactForAction(
                LinkSessionService.ACTION_STOP_IDLE_KEEPALIVE,
            ),
        )
        assertTrue(
            LinkSessionService.matchesIdleKeepAliveSession(
                activeSessionId = "active-session",
                requestedSessionId = "active-session",
            ),
        )
    }

    @Test
    fun foregroundActionsRequireForegroundServiceDelivery() {
        assertEquals(
            SessionServiceCommandDispatch.StartForegroundService,
            LinkSessionService.commandDispatch(
                action = LinkSessionService.ACTION_START_IDLE_KEEPALIVE,
                sessionId = "session",
                serviceActive = false,
            ),
        )
    }

    @Test
    fun foregroundSessionUpdatesCanRemainSticky() {
        val policy = LinkSessionService.startPolicyForAction(LinkSessionService.ACTION_UPDATE)

        assertEquals(Service.START_STICKY, policy.startResult)
    }

    @Test
    fun idleKeepaliveTimesOutOnlyAfterDisconnectedWindowElapses() {
        assertFalse(
            LinkSessionService.idleKeepAliveTimedOut(
                lastSuccessfulPingAtEpochMillis = 1_000L,
                nowEpochMillis = 61_000L,
                timeoutMillis = 60_000L,
            )
        )
        assertTrue(
            LinkSessionService.idleKeepAliveTimedOut(
                lastSuccessfulPingAtEpochMillis = 1_000L,
                nowEpochMillis = 61_001L,
                timeoutMillis = 60_000L,
            )
        )
    }

    @Test
    fun reconnectRetryDelayIsBoundedByRemainingDisconnectedTimeout() {
        val delay = LinkSessionService.idleKeepAliveDelayBeforeTimeout(
            lastSuccessfulPingAtEpochMillis = 1_000L,
            nowEpochMillis = 55_000L,
            retryDelayMillis = 15_000L,
            timeoutMillis = 60_000L,
        )

        assertEquals(6_000L, delay)
    }

    @Test
    fun failedIdlePingShowsReconnectingBeforeTimeout() {
        val state = LinkSessionService.idleKeepAliveNotificationStateAfterPing(
            pingSucceeded = false,
            lastSuccessfulPingAtEpochMillis = 1_000L,
            nowEpochMillis = 61_000L,
            timeoutMillis = 60_000L,
        )

        assertEquals(IdleKeepAliveNotificationState.Reconnecting, state)
    }

    @Test
    fun successfulIdlePingReturnsToBackgroundSync() {
        val state = LinkSessionService.idleKeepAliveNotificationStateAfterPing(
            pingSucceeded = true,
            lastSuccessfulPingAtEpochMillis = 1_000L,
            nowEpochMillis = 61_001L,
            timeoutMillis = 60_000L,
        )

        assertEquals(IdleKeepAliveNotificationState.BackgroundSync, state)
    }

    @Test
    fun failedIdlePingAfterTimeoutShowsDisconnectedTimeout() {
        val state = LinkSessionService.idleKeepAliveNotificationStateAfterPing(
            pingSucceeded = false,
            lastSuccessfulPingAtEpochMillis = 1_000L,
            nowEpochMillis = 61_001L,
            timeoutMillis = 60_000L,
        )

        assertEquals(IdleKeepAliveNotificationState.DisconnectedTimeout, state)
    }
}
