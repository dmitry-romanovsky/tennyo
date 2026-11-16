package app.tennyo.host.engine.agents

import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.jsOptions

/** The agents configured for this user, read fresh on each call so an edit to the files needs no restart. */
fun nodeLocalAgentServers(): List<AgentServer> {
    val fs = js("require('node:fs')")
    val os = js("require('node:os')")
    return localAgentServers(
        home = (os.homedir() as String).replace('\\', '/'),
        platform = registryPlatform(os.platform() as String, os.arch() as String),
    ) { path -> runCatching { fs.readFileSync(path, "utf8") as String }.getOrNull() }
}

/**
 * Starts agents as child processes of the extension host. On Windows the command runs through the shell, because the
 * usual launchers (`npx`, `uvx`) are `.cmd` scripts.
 */
object NodeAgentLauncher : AgentLauncher {
    private val LOG = HostLogger("NodeAgentLauncher")

    override fun launch(
        server: AgentServer,
        workingDirectory: String,
        onLine: (String) -> Unit,
        onExit: () -> Unit,
    ): AgentProcess {
        val childProcess = js("require('node:child_process')")
        val readline = js("require('node:readline')")
        val environment = js("Object.assign({}, process.env)")
        server.env.forEach { (name, value) -> environment[name] = value }
        val windows = js("process.platform") == "win32"
        LOG.info("Starting agent ${server.id}: ${(listOf(server.command) + server.args).joinToString(" ")}")
        val child = childProcess.spawn(
            server.command,
            server.args.toTypedArray(),
            jsOptions("cwd" to workingDirectory, "env" to environment, "shell" to windows),
        )
        // The last stderr lines say why an agent that exits early failed, as on IntelliJ.
        val stderrTail = ArrayDeque<String>()
        var exited = false
        // `close` comes after stdout is drained, so the agent's last answer is read before the exit is reported; a
        // spawn that fails reports `error` and may never close.
        val exit = { reason: String ->
            if (!exited) {
                exited = true
                LOG.info("Agent ${server.id} exited ($reason). Last output:\n${stderrTail.joinToString("\n")}")
                onExit()
            }
        }
        readline.createInterface(jsOptions("input" to child.stdout)).on("line") { line: String -> onLine(line) }
        child.stderr.on("data") { chunk: dynamic ->
            (chunk.toString() as String).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                LOG.debug { line }
                stderrTail.addLast(line)
                if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
            }
        }
        // Writing to an agent that already exited fails on stdin; unhandled, that error would take the extension host.
        child.stdin.on("error") { error: dynamic -> LOG.debug { "Agent ${server.id} stdin: ${error?.message}" } }
        child.on("error") { error: dynamic -> exit("failed: ${error?.message}") }
        child.on("close") { code: dynamic -> exit("code $code") }
        return object : AgentProcess {
            override fun send(line: String) {
                if (!exited) child.stdin.write("$line\n")
            }

            override fun stop() {
                // Through the shell, `kill` would end only `cmd.exe` and leave the agent it started running.
                if (windows && child.pid != null) {
                    childProcess.spawn("taskkill", arrayOf("/pid", "${child.pid}", "/T", "/F"))
                        .on("error") { error: dynamic -> LOG.warn("Could not stop ${server.id}: ${error?.message}") }
                } else {
                    child.kill()
                }
            }
        }
    }
}

private const val STDERR_TAIL_LINES = 20
