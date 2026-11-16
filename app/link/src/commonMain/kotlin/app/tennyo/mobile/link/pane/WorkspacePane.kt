package app.tennyo.mobile.link.pane

import app.tennyo.contract.LinkPush

/**
 * One pane of the workspace (files, run, terminal or git) as the workspace drives it over a link session: the pane
 * owns its state and its requests; the workspace decides which pane is shown and when the pane loads and syncs.
 */
interface WorkspacePane {
    /**
     * Loads what the pane needs after a (re)connect. Completes before live events resume, so no event is applied to an
     * older snapshot. A pane that is not [shown] loads only what the workspace shows regardless of the selected pane.
     */
    suspend fun loadSnapshot(shown: Boolean)

    /** Reloads what the pane shows, as when the user brought it on screen or asked for a refresh. */
    suspend fun sync(pass: SyncPass)

    /**
     * Applies a host push if this pane owns its type and ignores it otherwise. Throws when handling failed, so the
     * workspace leaves the push unacknowledged and the host sends it again.
     */
    fun apply(push: LinkPush)

    /** Sync stopped: the link dropped, or — [forBackground] — the app left the foreground; pending edits drop too. */
    fun suspendSync(forBackground: Boolean) = Unit

    /** The pane came on screen. */
    fun onShown() = Unit

    /** Closes the pane's innermost destination; `false` when it has none and back leaves the workspace. */
    fun onBack(): Boolean = false
}

/** How much one sync pass may fetch. */
data class SyncPass(
    /** The pane is on screen; hidden panes sync only what the workspace shows regardless of the selected pane. */
    val shown: Boolean,
    /** Tab lists and other metadata. Skipped while live sync is ready, since events keep them current. */
    val metadata: Boolean,
    /** Refetch the shown content even while it is fresh. */
    val explicitContent: Boolean,
    val nowEpochMillis: Long,
) {
    companion object {
        /** A refresh right after the user brought the pane on screen or changed what it shows. */
        fun userRefresh(nowEpochMillis: Long = nowEpochMillis()): SyncPass =
            SyncPass(shown = true, metadata = true, explicitContent = true, nowEpochMillis)
    }
}
