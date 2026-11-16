package app.tennyo.host.engine.agents

/**
 * The agents configured on this computer, from the files other ACP clients keep: JetBrains AI Assistant's
 * `~/.jetbrains/acp.json` and Devin Desktop's own agents file. [readText] answers `null` for a file that is not there;
 * [platform] is the registry's platform key for this machine.
 */
fun localAgentServers(home: String, platform: String, readText: (String) -> String?): List<AgentServer> =
    listOfNotNull(
        readText("$home/.jetbrains/acp.json")?.let { agentServersFromMap("jetbrains", it) },
        readText(devinAgentsFile(home, platform))?.let { agentServersFromRegistry("devin", platform, it) },
    ).flatten()

/** Devin Desktop keeps its agents file in `~/.windsurf`, except on Windows, where it uses VS Code's user directory. */
private fun devinAgentsFile(home: String, platform: String): String =
    if (platform.startsWith("windows")) {
        "$home/AppData/Roaming/Code/User/acp/registry.json"
    } else {
        "$home/.windsurf/acp/registry.json"
    }

/** The ACP registry's platform key for an OS and CPU name as the JVM or Node reports them. */
fun registryPlatform(os: String, arch: String): String {
    val system = when {
        os.startsWith("win", ignoreCase = true) -> "windows"
        os.startsWith("mac", ignoreCase = true) || os.equals("darwin", ignoreCase = true) -> "darwin"
        else -> "linux"
    }
    val cpu = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    return "$system-$cpu"
}
