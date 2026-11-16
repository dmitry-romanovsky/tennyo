package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkFrame
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.LiveSync
import app.tennyo.mobile.link.causes
import app.tennyo.mobile.link.endsSession
import app.tennyo.mobile.link.isDeviceNotApproved
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.toLinkFault
import app.tennyo.mobile.link.transport.LinkChannels
import app.tennyo.mobile.link.transport.OpenLinkChannel
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps the channel open while the session is live: reopening with backoff when it drops, resuming after the last
 * applied push, loading a fresh snapshot when the host cannot resume, and handing every push to [onPush].
 */
@Suppress("LongParameterList")
internal class ChannelAttachment(
    private val core: SessionCore,
    private val status: ConnectionStatus,
    private val channels: LinkChannels,
    private val backgroundIdle: BackgroundIdleState,
    private val surfaceCommands: SurfaceCommands,
    private val notification: NotificationBridge,
    private val signals: LifecycleSignals,
    private val onPush: suspend (LinkFrame.Push) -> Unit,
) {
    private var channelJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0

    fun start(ticket: LinkTicket) {
        retryJob?.cancel()
        retryJob = null
        val previous = channelJob
        channelJob = null
        resetConnectionState()
        previous?.cancel()
        if (status.channelLossStartedAtEpochMillis == null) {
            core.updateLive { LinkState.Live(LiveSync.Attaching) }
        }
        status.update { it.copy(sessionEndedByHost = false) }
        var opened = false
        channelJob = core.scope.launch {
            // Pushes are queued while the snapshot loads, so none is applied to an older snapshot than itself.
            val queued = Channel<LinkFrame.Push>(Channel.UNLIMITED)
            val delivery = core.scope.launch {
                for (push in queued) {
                    runCatchingCancellable { onPush(push) }.onFailure { failure ->
                        Logger.w("Applying a host push failed; it stays unacknowledged", failure)
                    }
                }
            }
            try {
                channels.run(
                    ticket = ticket,
                    lastPushSeq = status.lastPushSeq,
                    onOpened = { channel ->
                        opened = true
                        core.scope.launch { accept(channel) }
                    },
                    onPush = { push -> queued.trySend(push) },
                )
                if (!backgroundIdle.markSyncDetachedForBackground() && isStillLive()) {
                    retry(ticket, LinkFault.StreamClosed(beforeLiveSync = !opened))
                }
            } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
                handleFailure(ticket, throwable)
            } finally {
                queued.close()
                delivery.cancel()
                if (core.channel.value != null) resetConnectionState()
            }
        }
    }

    /** The host accepted the channel: reload what the panes show when it could not resume, then go live. */
    private suspend fun accept(channel: OpenLinkChannel) {
        retryAttempt = 0
        core.channel.value = channel
        if (channel.opened.snapshotRequired) status.update { it.copy(lastPushSeq = null) }
        loadSnapshotIfDue(hostRequires = channel.opened.snapshotRequired)
        markReady()
        signals.markConnectionHealthy()
    }

    /** Loads the panes' snapshot when the host could not resume, or the panes are new ([SessionCore.snapshotDue]). */
    suspend fun loadSnapshotIfDue(hostRequires: Boolean = false) {
        if (!core.snapshotDue(hostRequires)) return
        runCatchingCancellable { core.attachment?.loadSnapshot(core.project.value.capabilities) }
            .onFailure { Logger.w("Loading the pane snapshot failed", it) }
    }

    private fun handleFailure(ticket: LinkTicket, throwable: Throwable) {
        val fault = throwable.toLinkFault()
        when {
            status.sessionEndedByHost -> Unit
            // The host forgot this device while we were away; pairing has to be redone.
            fault.isDeviceNotApproved -> signals.connect(resume = false)
            fault.endsSession -> signals.handleConnectionFailure(fault)
            backgroundIdle.markSyncDetachedForBackground() -> Unit
            core.state.value is LinkState.Live -> retry(ticket, fault)
            else -> signals.markConnectionLost(retrying = true, throwable = fault)
        }
    }

    private fun isStillLive(): Boolean = !status.sessionEndedByHost && core.state.value is LinkState.Live

    /** Reopens while the session itself stays valid; only a loss that lasts is shown. */
    private fun retry(ticket: LinkTicket, throwable: Throwable) {
        if (throwable.isTlsConnectFailure()) {
            Logger.w("The channel's TLS connect failed before the upgrade; retrying")
        } else {
            Logger.w("The channel dropped; retrying while the session stays valid", throwable)
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val lossStartedAt = status.markChannelLoss(now)
        if (channelLossIsUserVisible(lossStartedAt, now)) {
            core.updateLive { LinkState.Live(LiveSync.Reattaching) }
        }
        if (retryJob?.isActive == true) return
        val retryDelay = channelRetryDelayMillis(retryAttempt)
        retryAttempt++
        retryJob = core.scope.launch {
            delay(retryDelay.milliseconds)
            if (backgroundIdle.markSyncDetachedForBackground()) return@launch
            if (isStillLive()) {
                retryJob = null
                start(ticket)
            }
        }
    }

    fun markReady() {
        status.update { it.copy(channelLossStartedAtEpochMillis = null, reconnectAttempts = 0) }
        signals.clearConnectionTimeout()
        notification.update(SessionStatus.Connected)
        core.linkState = LinkState.Live(LiveSync.Ready)
    }

    /** Clears the channel bookkeeping without cancelling the job itself. */
    fun resetConnectionState() {
        core.channel.value = null
        surfaceCommands.reset()
    }

    fun cancelRetry() {
        retryJob?.cancel()
    }

    /** Detaches without reporting anything, as when entering background idle. */
    fun detachForBackground() {
        val previous = channelJob
        channelJob = null
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        resetConnectionState()
        previous?.cancel()
    }

    fun cancel() {
        channelJob?.cancel()
        retryJob?.cancel()
    }
}

internal fun channelRetryDelayMillis(attempt: Int): Long =
    RETRY_DELAYS_MILLIS[attempt.coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)]

private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

/** A channel drop shorter than [CHANNEL_DEGRADED_AFTER_MILLIS] is not worth a banner. */
internal fun channelLossIsUserVisible(startedAtEpochMillis: Long?, nowEpochMillis: Long): Boolean =
    startedAtEpochMillis != null && nowEpochMillis - startedAtEpochMillis >= CHANNEL_DEGRADED_AFTER_MILLIS

internal const val CHANNEL_DEGRADED_AFTER_MILLIS = 10_000L

private fun Throwable.isTlsConnectFailure(): Boolean =
    causes().any { throwable -> throwable::class.simpleName == "SSLHandshakeException" }
