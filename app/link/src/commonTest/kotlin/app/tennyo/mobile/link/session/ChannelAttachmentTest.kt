package app.tennyo.mobile.link.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelAttachmentTest {
    @Test
    fun reopeningUsesBoundedExponentialDelays() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
            (0..5).map(::channelRetryDelayMillis),
        )
        assertEquals(30_000L, channelRetryDelayMillis(99))
    }

    @Test
    fun aChannelLossOnlyBecomesVisibleAfterItsGracePeriod() {
        val startedAt = 1_000L

        assertFalse(channelLossIsUserVisible(startedAt, 10_999L))
        assertTrue(channelLossIsUserVisible(startedAt, 11_000L))
        assertFalse(channelLossIsUserVisible(null, 11_000L))
    }
}
