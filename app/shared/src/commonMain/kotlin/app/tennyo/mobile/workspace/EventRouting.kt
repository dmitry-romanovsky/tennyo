package app.tennyo.mobile.workspace

import app.tennyo.contract.LinkFrame
import app.tennyo.mobile.link.pane.WorkspacePane
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

/**
 * Hands [frame] to every pane, each taking the push types it owns. `false` when one failed to apply it: the push then
 * stays unacknowledged, so the session does not checkpoint past it and the host sends it again on reconnect.
 */
internal fun List<WorkspacePane>.applyAll(frame: LinkFrame.Push): Boolean =
    try {
        forEach { it.apply(frame.push) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Logger.e("Applying push ${frame.seq} failed; it will not be acknowledged", failure)
        false
    }
