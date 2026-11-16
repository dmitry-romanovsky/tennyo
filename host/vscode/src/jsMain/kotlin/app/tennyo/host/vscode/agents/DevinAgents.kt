package app.tennyo.host.vscode.agents

import app.tennyo.host.engine.agents.AgentServer
import app.tennyo.host.engine.agents.devinAgentServers
import app.tennyo.host.engine.agents.registryPlatform
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.vscode.api.VsCodeEnv
import app.tennyo.host.vscode.api.VsCodeWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise

/**
 * The ACP agents Devin Desktop offers in its own chat. Devin keeps no list of them on disk — it asks its servers — so
 * this reads the public ACP registry that list mirrors, once, and offers the agents `devin.acp.enabledAgents` switches
 * on. Outside Devin the setting is absent and this offers nothing.
 */
class DevinAgents(private val scope: CoroutineScope) {
    private var registry: String? = null
    private var lastFetchMillis = 0L

    init {
        servers()
    }

    fun servers(): List<AgentServer> {
        val enabled = enabledAgents().ifEmpty { return emptyList() }
        val json = registry
        if (json == null) {
            fetchRegistry()
            return emptyList()
        }
        return devinAgentServers(json, platform(), enabled, bundledDevin())
    }

    private fun enabledAgents(): Set<String> {
        val setting = VsCodeWorkspace.getConfiguration("devin").get("acp.enabledAgents") ?: return emptySet()
        val keys = js("Object.keys")(setting) as Array<String>
        return keys.filter { setting[it] == true }.toSet()
    }

    private fun fetchRegistry() {
        val now = hostWallClockMillis()
        if (now - lastFetchMillis < RETRY_MILLIS) return
        lastFetchMillis = now
        scope.launch {
            try {
                val response = (js("fetch")(REGISTRY_URL) as Promise<dynamic>).await()
                if (response.ok != true) throw IllegalStateException("HTTP ${response.status}")
                registry = (response.text() as Promise<String>).await()
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                LOG.info("The ACP registry Devin's agents come from did not load: ${failure.message}")
            }
        }
    }

    private fun platform(): String {
        val os = js("require('node:os')")
        return registryPlatform(os.platform() as String, os.arch() as String)
    }

    /** The agent Devin ships inside itself, where Devin starts it from. */
    private fun bundledDevin(): String? {
        val path = js("require('node:path')")
        val name = if (js("process.platform") == "win32") "devin.exe" else "devin"
        val binary = path.join(VsCodeEnv.appRoot, "extensions", "windsurf", "devin", "bin", name) as String
        return binary.takeIf { js("require('node:fs')").existsSync(it) as Boolean }
    }

    private companion object {
        val LOG = HostLogger("DevinAgents")
        const val REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
        const val RETRY_MILLIS = 60_000L
    }
}
