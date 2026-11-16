package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.isRateLimited
import app.tennyo.mobile.link.request
import app.tennyo.mobile.link.runCatchingCancellable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The periodic stats call: it keeps the host from treating this device as gone, doubles as the health check, and
 * measures the round trip the stats sheet shows.
 */
@Suppress("LongParameterList")
internal class PingLoop(
    private val core: SessionCore,
    private val status: ConnectionStatus,
    private val persistence: SessionPersistence,
    private val backgroundIdle: BackgroundIdleState,
    private val policy: PingPolicy,
    private val signals: LifecycleSignals,
) {
    private val measurements = PingMeasurements()
    private var pingJob: Job? = null
    private var statsAverageJob: Job? = null

    fun refreshStats() {
        val ticket = core.ticket.value ?: return
        core.scope.launch {
            val sentAt = now()
            runCatchingCancellable { requestStats() }
                .onSuccess { response ->
                    recordSuccess(ticket, sentAt)
                    signals.markConnectionHealthy(measurements.lastSuccessfulPingMillis, response.stats)
                }
                .onFailure { throwable ->
                    if (core.state.value is LinkState.Live) {
                        signals.scheduleReconnect(throwable)
                    } else {
                        Logger.w("Unable to refresh session stats", throwable)
                    }
                }
        }
    }

    fun setStatsVisible(open: Boolean) {
        statsAverageJob?.cancel()
        statsAverageJob = null
        val immediatePing = measurements.setStatsOpen(open)
        if (!open) return
        immediatePing?.let { ping -> core.stats.value = core.stats.value.copy(lastPingMillis = ping) }
        statsAverageJob = core.scope.launch {
            while (true) {
                delay(STATS_AVERAGE_INTERVAL_MILLIS.milliseconds)
                val average = measurements.openStatsAverageMillis() ?: continue
                core.stats.value = core.stats.value.copy(lastPingMillis = average)
            }
        }
    }

    fun start(ticket: LinkTicket) {
        pingJob?.cancel()
        pingJob = core.scope.launch {
            while (true) {
                delay(policy.currentIntervalMillis().milliseconds)
                val sentAt = now()
                runCatchingCancellable { requestStats() }
                    .onSuccess { response ->
                        recordSuccess(ticket, sentAt)
                        signals.markConnectionHealthy(
                            if (measurements.isStatsOpen()) null else measurements.lastSuccessfulPingMillis,
                            response.stats,
                        )
                    }
                    .onFailure(::onPingFailure)
            }
        }
    }

    private suspend fun requestStats(): LinkResult.Stats {
        val channel = core.channel.value ?: throw LinkFault.StreamClosed(beforeLiveSync = true)
        return channel.request<LinkResult.Stats>(LinkCall.Stats)
    }

    private fun onPingFailure(throwable: Throwable) {
        when {
            throwable.isRateLimited -> Logger.w("Session ping was rate limited; keeping the session connected")
            backgroundIdle.shouldSuppressConnectionLoss ->
                Logger.w("Ignoring a ping failure while background idle is pending", throwable)

            core.state.value is LinkState.Live -> signals.scheduleReconnect(throwable)
            else -> signals.markConnectionLost(retrying = true, throwable = throwable)
        }
    }

    fun cancel() {
        pingJob?.cancel()
        statsAverageJob?.cancel()
    }

    private fun recordSuccess(ticket: LinkTicket, sentAt: Long) {
        val receivedAt = now()
        status.update {
            it.copy(lastSuccessfulPingAtEpochMillis = maxOf(it.lastSuccessfulPingAtEpochMillis, receivedAt))
        }
        val lastSuccess = status.lastSuccessfulPingAtEpochMillis
        // A healthy background keep-alive stays resumable after process death only if the record hears about it.
        if (lastSuccess - status.lastPersistedPingAtEpochMillis >= CREDENTIAL_PING_PERSIST_INTERVAL_MILLIS) {
            status.update { it.copy(lastPersistedPingAtEpochMillis = lastSuccess) }
            persistence.recordSuccessfulPing(ticket.sessionId, lastSuccess)
        }
        measurements.recordSuccess(durationMillis = receivedAt - sentAt)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val STATS_AVERAGE_INTERVAL_MILLIS = 1_000L
        const val CREDENTIAL_PING_PERSIST_INTERVAL_MILLIS = 60_000L
    }
}
