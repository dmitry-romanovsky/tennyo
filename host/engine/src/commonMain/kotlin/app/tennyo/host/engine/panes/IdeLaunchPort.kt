package app.tennyo.host.engine.panes

import app.tennyo.contract.LaunchCandidate
import app.tennyo.contract.LaunchConfigurationsState

/**
 * Launch configurations and the launch tabs they produce. Tab ids are the bridge's own stable ids; the core keeps the
 * selection, the output buffers, and everything it sends.
 */
interface IdeLaunchPort {
    fun configurations(): LaunchConfigurationsState

    fun candidatesForFile(path: String): List<LaunchCandidate> = emptyList()

    /** Selects the configuration with [id], or clears the selection when [id] is `null`. */
    fun selectConfiguration(id: String?)

    /** Starts the configuration and returns its name, or `null` when it does not exist or cannot run. */
    fun start(id: String): String?

    /** Asks every running process of the configuration to stop, returning what was stopped. */
    fun stop(id: String): List<StoppedLaunch>

    /** The current run tabs, or `null` when the IDE could not be read. Each tab is offered to [watcher]. */
    fun tabs(watcher: LaunchTabWatcher): LaunchTabsSnapshot?

    fun hasTab(id: String): Boolean

    fun selectTab(id: String)

    /** Stops the tab's process without asking and removes the tab; `false` when the tab is gone. */
    fun closeTab(id: String): Boolean

    /**
     * What a selected-output read needs from the tab: its identity, whether it runs, and up to [maxChars] of what its
     * console already shows. `null` when the tab is gone.
     */
    fun outputSource(id: String, maxChars: Int, watcher: LaunchTabWatcher): LaunchTabOutputSource?
}

/**
 * Creates one session's launch bridge, or `null` when the IDE has no launch configurations. The bridge lives until
 * [LaunchPortLease.release].
 */
fun interface IdeLaunchPortFactory {
    fun create(): LaunchPortLease?

    /** Whether this IDE has launch configurations a bridge can drive at all. */
    fun isAvailable(): Boolean = true
}

class LaunchPortLease(val bridge: IdeLaunchPort, private val onRelease: () -> Unit) {
    fun release() = onRelease()
}

/**
 * Lets a session hear when a run tab goes away. The bridge asks [shouldWatch] for every tab it reads and, when that
 * returns `true`, calls [onTabClosed] once the tab is disposed.
 */
interface LaunchTabWatcher {
    fun shouldWatch(tabId: String): Boolean

    fun onTabClosed(tabId: String)
}

data class LaunchTabInfo(
    val id: String,
    val configurationId: String?,
    val displayName: String?,
    val runProfileName: String?,
    val configurationName: String?,
    val isRunning: Boolean,
)

data class LaunchTabsSnapshot(val tabs: List<LaunchTabInfo>, val selectedTabId: String?)

data class LaunchTabOutputSource(val configurationId: String?, val isRunning: Boolean, val consoleText: String?)

data class StoppedLaunch(val displayName: String, val tabId: String)
