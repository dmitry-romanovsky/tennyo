package app.tennyo.mobile.link.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStatusTest {

    @Test
    fun updatesPublishANewSnapshotAndLeaveTheOldOneIntact() {
        val status = ConnectionStatus()
        val before = status.current

        status.update { it.copy(reconnectAttempts = it.reconnectAttempts + 1, lastPushSeq = 4L) }

        assertEquals(0, before.reconnectAttempts)
        assertNull(before.lastPushSeq)
        assertNotSame(before, status.current)
        assertEquals(1, status.reconnectAttempts)
        assertEquals(4L, status.lastPushSeq)
    }

    @Test
    fun concurrentStyleIncrementsDoNotLoseWrites() {
        val status = ConnectionStatus()

        repeat(1_000) { status.update { it.copy(reconnectAttempts = it.reconnectAttempts + 1) } }

        assertEquals(1_000, status.reconnectAttempts)
    }

    @Test
    fun markChannelLossKeepsTheFirstLossTime() {
        val status = ConnectionStatus()

        assertEquals(100L, status.markChannelLoss(nowEpochMillis = 100L))
        assertEquals(100L, status.markChannelLoss(nowEpochMillis = 250L))
        assertEquals(100L, status.channelLossStartedAtEpochMillis)
    }

    @Test
    fun resumesOnlyASessionTheHostHasNotEnded() {
        assertTrue(ConnectionStatus(ConnectionSnapshot(hasPersistedResumableSession = true)).shouldResume)
        assertTrue(ConnectionStatus(ConnectionSnapshot(sessionConnectedInMemory = true)).shouldResume)
        assertFalse(
            ConnectionStatus(ConnectionSnapshot(sessionEndedByHost = true, hasPersistedResumableSession = true))
                .shouldResume,
        )
        assertFalse(ConnectionStatus().shouldResume)
    }
}
