package app.tennyo.host.engine.panes

import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

data class PendingLaunchOutput(
    val tabId: String,
    val configurationId: String,
    val text: String,
    val isRunning: Boolean,
)

class LaunchOutputAccumulator(
    private val characterLimit: Int,
) {
    private val monitor = HostMonitor()
    private val pending = LinkedHashMap<String, MutableOutput>()

    fun append(tabId: String, configurationId: String, text: String, isRunning: Boolean) = monitor.guarded {
        val output = pending[tabId]
        if (output == null) {
            pending[tabId] = MutableOutput(
                configurationId = configurationId,
                text = LaunchOutputBuffer(characterLimit).apply { append(text) },
                isRunning = isRunning,
            )
        } else {
            output.text.append(text)
            output.isRunning = isRunning
        }
    }

    fun drain(tabId: String? = null): List<PendingLaunchOutput> = monitor.guarded {
        val entries = if (tabId == null) {
            pending.map { it.key to it.value }.also { pending.clear() }
        } else {
            listOfNotNull(pending.remove(tabId)?.let { tabId to it })
        }
        entries.map { (id, output) ->
            PendingLaunchOutput(id, output.configurationId, output.text.snapshot(), output.isRunning)
        }
    }

    fun isEmpty(): Boolean = monitor.guarded { pending.isEmpty() }

    fun clear() = monitor.guarded { pending.clear() }

    private data class MutableOutput(
        val configurationId: String,
        val text: LaunchOutputBuffer,
        var isRunning: Boolean,
    )
}
