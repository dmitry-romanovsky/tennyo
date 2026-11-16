package app.tennyo.host.engine.panes

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalTab

interface IdeTerminalPort {
    fun tabs(includeSnapshots: Boolean = true): List<TerminalTab>

    fun snapshot(terminalId: String): TerminalSnapshot?

    fun selectedTerminalId(): String?

    fun ensureOpen(): Boolean

    fun create(): TerminalTab?

    fun write(terminalId: String, text: String): Boolean

    fun close(terminalId: String): Boolean

    fun select(terminalId: String): Boolean
}

/** What a terminal bridge reports back to the session that owns it. */
class TerminalPortCallbacks(
    val onTabsChanged: () -> Unit,
    val onFrame: (String, TerminalSnapshot) -> Unit,
    val shouldReadFrame: (String) -> Boolean,
)

/**
 * Creates one session's terminal bridge, or `null` when the IDE has no usable terminal. The bridge lives until
 * [TerminalPortLease.release].
 */
fun interface IdeTerminalPortFactory {
    fun create(callbacks: TerminalPortCallbacks): TerminalPortLease?

    /** Whether this IDE has a terminal a bridge can drive at all. */
    fun isAvailable(): Boolean = true
}

class TerminalPortLease(val bridge: IdeTerminalPort, private val onRelease: () -> Unit) {
    fun release() = onRelease()
}
