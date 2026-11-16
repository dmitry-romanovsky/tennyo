package app.tennyo.host.engine.agents

import app.tennyo.host.engine.platform.HostLogger

/** A running agent: its stdin, and a way to end it together with anything it started. */
interface AgentProcess {
    fun send(line: String)

    fun stop()
}

/** Starts agent processes; each host platform supplies its own (a JVM process, a Node child process). */
fun interface AgentLauncher {
    /**
     * Starts [server] in [workingDirectory]. [onLine] gets each line of its stdout and [onExit] its end, both on
     * whatever thread the platform reads on. Throws when the command cannot be started at all.
     */
    fun launch(
        server: AgentServer,
        workingDirectory: String,
        onLine: (String) -> Unit,
        onExit: () -> Unit,
    ): AgentProcess
}

/**
 * What a host offers the Chat pane: the ACP agents the user configured and how to start one, and any [sources] of
 * agents the host does not launch itself.
 */
class IdeAgents(
    val launcher: AgentLauncher,
    val servers: () -> List<AgentServer>,
    val sources: List<ChatSource> = emptyList(),
) {
    /** Whether Chat has anything to offer. A source that fails counts as empty: Chat must never stop a session. */
    fun hasAny(): Boolean = servers().isNotEmpty() || sources.any { source ->
        runCatching { source.agents().isNotEmpty() }
            .onFailure { LOG.warn("A chat agent source failed; its agents are left out: ${it.message}", it) }
            .getOrDefault(false)
    }

    private companion object {
        val LOG = HostLogger("IdeAgents")
    }
}
