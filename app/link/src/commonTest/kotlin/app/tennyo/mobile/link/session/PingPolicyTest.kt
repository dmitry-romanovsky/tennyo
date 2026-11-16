package app.tennyo.mobile.link.session

import app.tennyo.contract.SessionTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PingPolicyTest {
    @Test
    fun approvedDeviceIdleTimeoutIsThreeMinutes() {
        assertEquals(3 * 60 * 1_000L, SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS)
    }

    @Test
    fun foregroundAndBackgroundUseDifferentPingIntervals() {
        val policy = PingPolicy()

        assertEquals(3_000L, policy.currentIntervalMillis())

        policy.visibility = AppVisibility.Background

        assertEquals(15_000L, policy.currentIntervalMillis())
    }

    @Test
    fun statsOpenShowsLastSuccessfulPingImmediatelyThenAverage() {
        val measurements = PingMeasurements()
        measurements.recordSuccess(durationMillis = 120L)

        assertEquals(120L, measurements.setStatsOpen(true))
        assertEquals(120L, measurements.openStatsAverageMillis())

        measurements.recordSuccess(durationMillis = 80L)
        measurements.recordSuccess(durationMillis = 100L)

        assertEquals(90L, measurements.openStatsAverageMillis())
    }

    @Test
    fun closedStatsDoNotMaintainAverageWindow() {
        val measurements = PingMeasurements()
        measurements.recordSuccess(durationMillis = 120L)
        measurements.setStatsOpen(true)
        measurements.recordSuccess(durationMillis = 80L)

        assertNull(measurements.setStatsOpen(false))
        assertNull(measurements.openStatsAverageMillis())
    }
}
