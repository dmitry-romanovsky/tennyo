package app.tennyo.host.engine.session

import app.tennyo.contract.LinkPush
import app.tennyo.contract.SessionStats
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

private const val STATS_EMISSION_INTERVAL_MILLIS = 1_000L

class SessionStatsController(
    private val session: HostSession,
) {
    private val monitor = HostMonitor()
    private val reconnectCount = AtomicLong(0L)
    private val totalUploadedBytes = AtomicLong(0L)
    private val totalDownloadedBytes = AtomicLong(0L)
    private var statsFlushJob: Job? = null
    private var lastStatsEmissionMillis: Long = session.wallClockMillis() - STATS_EMISSION_INTERVAL_MILLIS

    fun recordReconnect() {
        reconnectCount.addAndFetch(1L)
    }

    fun recordUploadBytes(bytes: Long) {
        if (bytes > 0) {
            totalUploadedBytes.addAndFetch(bytes)
        }
    }

    fun recordDownloadBytes(bytes: Long) {
        if (bytes > 0) {
            totalDownloadedBytes.addAndFetch(bytes)
        }
    }

    fun emitStats() {
        session.push(LinkPush.Stats(stats()))
    }

    fun emitStatsDebounced() {
        var emitImmediately = false
        monitor.guarded {
            val now = session.wallClockMillis()
            val delayMillis = (lastStatsEmissionMillis + STATS_EMISSION_INTERVAL_MILLIS - now).coerceAtLeast(0)
            if (delayMillis == 0L) {
                lastStatsEmissionMillis = now
                emitImmediately = true
            } else if (statsFlushJob?.isActive != true) {
                statsFlushJob = session.sessionScope.launch {
                    delay(delayMillis.milliseconds)
                    monitor.guarded {
                        lastStatsEmissionMillis = session.wallClockMillis()
                        statsFlushJob = null
                    }
                    emitStats()
                }
            }
        }
        if (emitImmediately) emitStats()
    }

    fun stats(): SessionStats {
        val ageSeconds = ((session.monotonicNow() - session.startedAtEpochMillis) / 1_000L).coerceAtLeast(1L)
        val uploaded = totalUploadedBytes.load()
        val downloaded = totalDownloadedBytes.load()
        return SessionStats(
            transport = "Tennyo host listener",
            uploadBytesPerSecond = uploaded / ageSeconds,
            downloadBytesPerSecond = downloaded / ageSeconds,
            totalUploadedBytes = uploaded,
            totalDownloadedBytes = downloaded,
            reconnectCount = reconnectCount.load().toInt(),
            sessionStartedAtEpochMillis = session.startedAtEpochMillis,
            hostName = session.owner.platform.hostLabel(),
        )
    }

    fun close() {
        statsFlushJob?.cancel()
        statsFlushJob = null
    }
}
