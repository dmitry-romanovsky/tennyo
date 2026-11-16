package app.tennyo.host.engine.panes

import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

class LaunchOutputBuffer(
    private val characterLimit: Int,
) {
    init {
        require(characterLimit > 0) { "characterLimit must be positive" }
    }

    private val monitor = HostMonitor()
    private val text = StringBuilder()
    private var retainedStart = 0

    fun seedIfEmpty(existingConsoleText: String) = monitor.guarded {
        if (retainedStart < text.length || existingConsoleText.isEmpty()) return@guarded
        appendBounded(existingConsoleText)
    }

    fun append(chunk: String) = monitor.guarded {
        appendBounded(chunk)
    }

    fun snapshot(): String = monitor.guarded { text.substring(retainedStart) }

    private fun appendBounded(chunk: String) {
        if (chunk.isEmpty()) return
        text.append(chunk)
        retainedStart = (text.length - characterLimit).coerceAtLeast(retainedStart)
        if (retainedStart >= characterLimit) {
            text.deleteRange(0, retainedStart)
            retainedStart = 0
        }
    }
}
