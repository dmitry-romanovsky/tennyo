package app.tennyo.host.engine.agents

import app.tennyo.host.engine.platform.HostLogger
import java.io.File
import kotlin.concurrent.thread

/** The agents configured for this user, read fresh on each call so an edit to the files needs no restart. */
fun jvmLocalAgentServers(): List<AgentServer> =
    localAgentServers(
        home = System.getProperty("user.home").replace('\\', '/'),
        platform = registryPlatform(System.getProperty("os.name"), System.getProperty("os.arch")),
    ) { path -> File(path).takeIf { it.isFile }?.readText() }

/**
 * Starts agents as child processes of the IDE. On Windows the command goes through `cmd /c`, because the usual
 * launchers (`npx`, `uvx`) are `.cmd` scripts that `ProcessBuilder` cannot start directly.
 */
object JvmAgentLauncher : AgentLauncher {
    private val LOG = HostLogger("JvmAgentLauncher")

    override fun launch(
        server: AgentServer,
        workingDirectory: String,
        onLine: (String) -> Unit,
        onExit: () -> Unit,
    ): AgentProcess {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val command = listOf(server.command) + server.args
        LOG.info("Starting agent ${server.id}: ${command.joinToString(" ")} in $workingDirectory")
        val process = try {
            ProcessBuilder(if (windows) listOf("cmd.exe", "/c") + command else command)
                .directory(File(workingDirectory))
                .apply { environment().putAll(server.env) }
                .start()
        } catch (failure: java.io.IOException) {
            LOG.warn("Agent ${server.id} could not start: ${failure.message}", failure)
            throw failure
        }
        // The last stderr lines say why an agent that exits early failed: a missing npx, a sign-in, a crash.
        val stderrTail = ArrayDeque<String>()
        thread(isDaemon = true, name = "Tennyo agent ${server.name}") {
            runCatching { process.inputStream.bufferedReader().forEachLine(onLine) }
            val code = runCatching { process.waitFor() }.getOrNull()
            if (code != null && code != 0) {
                val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }
                LOG.warn("Agent ${server.id} exited with code $code. Last output:\n$tail")
            } else {
                LOG.info("Agent ${server.id} exited (code $code).")
            }
            onExit()
        }
        // Agents log to stderr; draining it keeps a chatty agent from blocking on a full pipe.
        thread(isDaemon = true, name = "Tennyo agent ${server.name} stderr") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    LOG.debug { line }
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                    }
                }
            }
        }
        val input = process.outputStream.bufferedWriter()
        return object : AgentProcess {
            override fun send(line: String) = synchronized(input) {
                input.write(line)
                input.write("\n")
                input.flush()
            }

            override fun stop() {
                process.descendants().forEach { it.destroy() }
                process.destroy()
            }
        }
    }
}

private const val STDERR_TAIL_LINES = 20
