package app.tennyo.host.engine.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One ACP agent the user configured on this computer: the command that starts it speaking ACP over stdio.
 *
 * Command lines come only from the computer's configuration — files, editor settings, and registries the user's
 * editor already trusts — never from the phone, which names an agent by [id] alone. That is what keeps a paired phone
 * from running arbitrary programs on the host.
 */
data class AgentServer(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Where [command] comes from when it is relative: a registry archive a launcher downloads and unpacks first. */
    val archive: AgentArchive? = null,
    /** Whether the agent is on this computer already, or its first start downloads it. */
    val installed: Boolean = true,
)

/** A registry agent's release archive for this platform; [sha256] is checked when the registry gives one. */
data class AgentArchive(val url: String, val sha256: String?, val version: String)

/**
 * The `agent_servers` map JetBrains keeps in `~/.jetbrains/acp.json`; the VS Code `tennyo.acp.agentServers` setting
 * uses the same shape without the wrapper. These files belong to other tools, so a shape this does not understand
 * yields no agents rather than an error.
 */
fun agentServersFromMap(source: String, json: String): List<AgentServer> {
    val root = parse(json) as? JsonObject ?: return emptyList()
    val servers = root["agent_servers"] as? JsonObject ?: root
    return servers.mapNotNull { (name, entry) ->
        val server = entry as? JsonObject ?: return@mapNotNull null
        val command = server.string("command")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        AgentServer("$source:$name", name, command, server.strings("args"), server.stringMap("env"))
    }
}

/**
 * An ACP registry file (Devin Desktop's `~/.windsurf/acp/registry.json` uses the public registry's shape): agents
 * published through `npx` or `uvx`, or as a binary already installed for [platform] (`windows-x86_64`,
 * `darwin-aarch64`, `linux-x86_64`, …). An archive that still has to be downloaded is not something this starts.
 */
fun agentServersFromRegistry(
    source: String,
    platform: String,
    json: String,
    npx: String = "npx",
    excluded: Set<String> = emptySet(),
    downloadsArchives: Boolean = false,
    archivesOnPath: Boolean = false,
): List<AgentServer> {
    val agents = (parse(json) as? JsonObject)?.get("agents") as? JsonArray ?: return emptyList()
    return agents.mapNotNull { element ->
        val agent = element as? JsonObject ?: return@mapNotNull null
        val id = agent.string("id")?.takeIf(String::isNotBlank)?.takeIf { it !in excluded } ?: return@mapNotNull null
        val name = agent.string("name") ?: id
        val distribution = agent["distribution"] as? JsonObject ?: return@mapNotNull null
        val env = agent.stringMap("env")
        distribution.launch(platform, npx)?.let { (command, args) ->
            return@mapNotNull AgentServer("$source:$id", name, command, args, env)
        }
        val binary = (distribution["binary"] as? JsonObject)?.get(platform) as? JsonObject
        val command = binary?.string("cmd")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (archivesOnPath && binary.string("archive") != null) {
            val onPath = command.substringAfterLast('/')
            return@mapNotNull AgentServer("$source:$id", name, onPath, binary.strings("args"), env)
        }
        val url = binary.string("archive")?.takeIf { downloadsArchives && it.startsWith("https://") }
            ?: return@mapNotNull null
        val archive = AgentArchive(url, binary.string("sha256"), agent.string("version").orEmpty())
        AgentServer("$source:$id", name, command, binary.strings("args"), env, archive)
    }
}

/**
 * Devin Desktop's ACP agents: those `devin.acp.enabledAgents` switches on, from the public ACP registry that Devin's
 * own list mirrors, started as Devin starts them. Its bundled agent runs [bundledDevin] when Devin ships one; Devin
 * downloads no archive, so an agent released as one runs its command from PATH, where Devin expects it too.
 */
fun devinAgentServers(
    registryJson: String,
    platform: String,
    enabled: Set<String>,
    bundledDevin: String?,
): List<AgentServer> {
    val registry = agentServersFromRegistry("devin", platform, registryJson, archivesOnPath = true)
        .filter { it.id.removePrefix("devin:") in enabled }
    val bundled = bundledDevin?.takeIf { DEVIN_BUNDLED_AGENT in enabled }
        ?.let { AgentServer("devin:$DEVIN_BUNDLED_AGENT", "Devin Local", it, listOf("acp")) }
    return registry + listOfNotNull(bundled)
}

/** The agent Devin Desktop ships inside itself, which the public registry lists as a download instead. */
private const val DEVIN_BUNDLED_AGENT = "devin-cli"

/**
 * The registry agents switched off in JetBrains' own AI chat: `disabledAgents` in
 * `<config root>/acp-agents/installed.json`, named `acp.registry.<registry id>`.
 */
fun jetBrainsDisabledAgents(installedJson: String): Set<String> =
    ((parse(installedJson) as? JsonObject)?.get("disabledAgents") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removePrefix("acp.registry.") }
        .toSet()

private fun JsonObject.launch(platform: String, npxCommand: String): Pair<String, List<String>>? {
    (get("npx") as? JsonObject)?.let { npx ->
        val pkg = npx.string("package")?.takeIf(String::isNotBlank) ?: return null
        return npxCommand to listOf("-y", pkg) + npx.strings("args")
    }
    (get("uvx") as? JsonObject)?.let { uvx ->
        val pkg = uvx.string("package")?.takeIf(String::isNotBlank) ?: return null
        return "uvx" to listOf(pkg) + uvx.strings("args")
    }
    val binary = ((get("binary") as? JsonObject)?.get(platform) as? JsonObject) ?: return null
    // A command inside an archive (`./agent`, or a bare `agent.exe`) exists only once the archive is unpacked.
    if (binary.string("archive") != null) return null
    val command = binary.string("cmd")?.takeIf { it.isNotBlank() && !it.startsWith(".") } ?: return null
    return command to binary.strings("args")
}

private fun parse(json: String): JsonElement? = runCatching { LenientJson.parseToJsonElement(json) }.getOrNull()

private fun JsonObject.stringMap(key: String): Map<String, String> =
    (get(key) as? JsonObject)?.mapNotNull { (name, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
    }?.toMap().orEmpty()

private val LenientJson = Json { isLenient = true }
