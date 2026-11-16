package app.tennyo.mobile.link.session

import kotlin.math.roundToLong

internal const val PING_FOREGROUND_INTERVAL_MILLIS = 3_000L
internal const val PING_BACKGROUND_INTERVAL_MILLIS = 15_000L

internal enum class AppVisibility {
    Foreground,
    Background,
}

/** Pings often while the workspace is on screen and rarely while the session only keeps alive in the background. */
internal class PingPolicy {
    var visibility: AppVisibility = AppVisibility.Foreground

    fun currentIntervalMillis(): Long =
        when (visibility) {
            AppVisibility.Foreground -> PING_FOREGROUND_INTERVAL_MILLIS
            AppVisibility.Background -> PING_BACKGROUND_INTERVAL_MILLIS
        }
}

/** Round-trip times for the stats sheet: the latest ping, or the average while the sheet is open. */
internal class PingMeasurements {
    private val openStatsSamples = ArrayDeque<Long>()
    private var statsOpen = false

    var lastSuccessfulPingMillis: Long? = null
        private set

    fun isStatsOpen(): Boolean = statsOpen

    fun setStatsOpen(open: Boolean): Long? {
        statsOpen = open
        openStatsSamples.clear()
        return if (open) lastSuccessfulPingMillis else null
    }

    fun recordSuccess(durationMillis: Long) {
        val normalizedDuration = durationMillis.coerceAtLeast(0L)
        lastSuccessfulPingMillis = normalizedDuration
        if (statsOpen) {
            openStatsSamples.add(normalizedDuration)
        }
    }

    fun openStatsAverageMillis(): Long? = when {
        !statsOpen -> null
        openStatsSamples.isEmpty() -> lastSuccessfulPingMillis
        else -> openStatsSamples.average().roundToLong()
    }
}
