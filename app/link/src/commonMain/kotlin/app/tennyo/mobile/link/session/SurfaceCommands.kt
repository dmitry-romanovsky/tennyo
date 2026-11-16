package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkCall
import app.tennyo.mobile.link.ActiveSurface
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LiveSync
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The notices that tell the host which pane is on screen, so it pushes only that pane's updates. A notice identical to
 * the one queued or last sent is dropped unless forced.
 */
internal class SurfaceCommands(
    private val core: SessionCore,
) {
    private var pendingSend: Job? = null
    private val deduplicator = SurfaceDeduplicator()

    /** Sends [surface] to the host; `false` when live sync is not ready to carry it. */
    suspend fun send(surface: ActiveSurface): Boolean {
        val channel = core.channel.value?.takeIf { (core.state.value as? LinkState.Live)?.sync == LiveSync.Ready }
            ?: return false
        return runCatching { channel.call(surface.toCall()) }
            .onFailure { Logger.w("Unable to tell the IDE which pane is on screen", it) }
            .isSuccess
    }

    fun show(surface: ActiveSurface, force: Boolean = false) {
        if (!deduplicator.shouldQueue(surface, force)) return
        pendingSend?.cancel()
        pendingSend = core.scope.launch {
            var sent = false
            try {
                sent = send(surface)
                if (sent) deduplicator.markSent(surface)
            } finally {
                if (!sent) deduplicator.markNotSent(surface)
            }
        }
    }

    /** Drops any in-flight notice and what was sent; the channel it went over is gone. */
    fun reset() {
        pendingSend?.cancel()
        deduplicator.reset()
    }

    private fun ActiveSurface.toCall(): LinkCall =
        LinkCall.ShowPane(
            panel = panel,
            selectedFilePath = selectedFilePath,
            selectedLaunchTabId = selectedLaunchTabId,
            selectedTerminalId = selectedTerminalId,
        )
}

/** Drops a surface notice identical to the one queued or last sent, so metadata echoes do not re-announce it. */
internal class SurfaceDeduplicator {
    private var queuedSurface: ActiveSurface? = null
    private var sentSurface: ActiveSurface? = null

    fun shouldQueue(surface: ActiveSurface, force: Boolean = false): Boolean {
        if (!force && (surface == queuedSurface || surface == sentSurface)) return false
        queuedSurface = surface
        return true
    }

    fun markSent(surface: ActiveSurface) {
        if (queuedSurface == surface) queuedSurface = null
        sentSurface = surface
    }

    fun markNotSent(surface: ActiveSurface) {
        if (queuedSurface == surface) queuedSurface = null
    }

    fun reset() {
        queuedSurface = null
        sentSurface = null
    }
}
