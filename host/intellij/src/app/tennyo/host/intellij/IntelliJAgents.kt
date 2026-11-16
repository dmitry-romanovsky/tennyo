package app.tennyo.host.intellij

import app.tennyo.host.engine.agents.AgentArchive
import app.tennyo.host.engine.agents.AgentLauncher
import app.tennyo.host.engine.agents.AgentProcess
import app.tennyo.host.engine.agents.AgentServer
import app.tennyo.host.engine.agents.JvmAgentLauncher
import app.tennyo.host.engine.agents.agentServersFromRegistry
import app.tennyo.host.engine.agents.jetBrainsDisabledAgents
import app.tennyo.host.engine.agents.jvmLocalAgentServers
import app.tennyo.host.engine.agents.registryPlatform
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.io.Decompressor.ErrorHandlerChoice.BAIL_OUT
import com.intellij.util.io.Decompressor.ErrorHandlerChoice.SKIP
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The agents a session can chat with here: the user's own `~/.jetbrains/acp.json` and Devin Desktop's registry, then
 * the public ACP registry IntelliJ's AI chat offers, as IntelliJ caches it under its system directory. Agents switched
 * off in IntelliJ are left out, and `npx` agents run on the Node IntelliJ downloaded for them when it has one.
 *
 * The result is logged whenever it changes, with where it looked, so "no Chat on the phone" can be read from idea.log.
 */
internal fun intelliJAgentServers(): List<AgentServer> {
    val local = jvmLocalAgentServers()
    val registry = registryAgentServers()
    val servers = (local + registry.servers).distinctBy { it.id }
    val report = if (servers.isEmpty()) {
        "Chat is not served: no ACP agents found. ${registry.note} " +
            "Add agents to ~/.jetbrains/acp.json or install them in IntelliJ's AI chat."
    } else {
        "Chat agents: ${local.size} from ~/.jetbrains/acp.json and Devin Desktop, " +
            "${registry.servers.size} from IntelliJ's ACP registry (${registry.note}): " +
            servers.joinToString { it.name }
    }
    synchronized(ReportLock) {
        if (report != lastReport) {
            lastReport = report
            LOG.info(report)
        }
    }
    return servers
}

private class RegistryAgents(val servers: List<AgentServer>, val note: String)

private fun registryAgentServers(): RegistryAgents {
    val system = PathManager.getSystemDir()
    val registryFile = system.resolve("acp-agents").resolve("registry.json")
    if (!registryFile.isRegularFile()) {
        return RegistryAgents(emptyList(), "IntelliJ's ACP registry cache is not at $registryFile.")
    }
    val installedFile = PathManager.getConfigDir().parent.resolve("acp-agents").resolve("installed.json")
    val disabled = runCatching { installedFile.takeIf { it.isRegularFile() }?.readText() }
        .onFailure { LOG.warn("Could not read $installedFile", it) }
        .getOrNull()
        ?.let(::jetBrainsDisabledAgents)
        .orEmpty()
    val npx = bundledNpx(system.resolve("acp-agents").resolve(".runtimes").resolve("node"))
    val servers = runCatching {
        agentServersFromRegistry(
            source = "intellij",
            platform = registryPlatform(System.getProperty("os.name"), System.getProperty("os.arch")),
            json = registryFile.readText(),
            npx = npx?.toString() ?: "npx",
            excluded = disabled,
            downloadsArchives = true,
        )
    }.onFailure { LOG.warn("Could not read IntelliJ's ACP registry at $registryFile", it) }.getOrDefault(emptyList())
    // Listed once per scan: the check runs for every registry agent on every agent list the phone asks for.
    val npxRuns = listOfNotNull(npx?.parent?.resolve("npm-cache"), userNpmCache()).flatMap { cache ->
        runCatching { Files.list(cache.resolve("_npx")).use { it.toList() } }.getOrDefault(emptyList())
    }
    val installed = servers.map { it.copy(installed = isInstalled(it, npxRuns)) }
    val note = "registry $registryFile, ${disabled.size} disabled, npx ${npx ?: "from PATH"}, " +
        "${installed.count { it.installed }} installed"
    return RegistryAgents(installed, note)
}

/**
 * Whether [server] is on this computer: an archive agent once Tennyo has unpacked it, an `npx` agent once npm has its
 * package in one of the [npxRuns] `npx` reads. A `uvx` agent counts as installed, since uv's cache is not something to
 * guess at.
 */
private fun isInstalled(server: AgentServer, npxRuns: List<Path>): Boolean {
    val archive = server.archive
    val npxPackage = server.args.takeIf { it.firstOrNull() == "-y" }?.getOrNull(1)
    if (archive != null || npxPackage == null) {
        return archive == null || IntelliJAgentLauncher.home(server.id, archive).exists()
    }
    val name = npxPackage.substringBeforeLast('@').ifEmpty { npxPackage }
    return npxRuns.any { it.resolve("node_modules").resolve(name).resolve("package.json").isRegularFile() }
}

/** The user's own npm cache, which a global `npx` fills. */
private fun userNpmCache(): Path? {
    val home = Path.of(System.getProperty("user.home"))
    val windowsCache = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "npm-cache") }
    return listOfNotNull(windowsCache, home.resolve(".npm")).firstOrNull { it.exists() }
}

/** The newest Node IntelliJ keeps for registry agents, as its `npx` launcher; `null` when it has none yet. */
private fun bundledNpx(nodeRoot: Path): Path? {
    if (!nodeRoot.exists()) return null
    val windows = System.getProperty("os.name").startsWith("Windows")
    return Files.list(nodeRoot).use { versions ->
        versions.toList().sortedByDescending { it.name }
            .map { if (windows) it.resolve("npx.cmd") else it.resolve("bin").resolve("npx") }
            .firstOrNull { it.isRegularFile() }
    }
}

/**
 * [JvmAgentLauncher], after unpacking a registry agent's release archive on its first start — the download IntelliJ's
 * own AI chat does for such agents, into Tennyo's own cache: `<system>/tennyo-agents/<agent>/<version>`. The archive is
 * checked against the registry's sha256 when it has one, and the command must stay inside what it unpacked.
 */
internal object IntelliJAgentLauncher : AgentLauncher {
    override fun launch(
        server: AgentServer,
        workingDirectory: String,
        onLine: (String) -> Unit,
        onExit: () -> Unit,
    ): AgentProcess {
        val archive = server.archive ?: return JvmAgentLauncher.launch(server, workingDirectory, onLine, onExit)
        val home = unpacked(server.id, archive)
        val command = home.resolve(server.command).normalize()
        require(command.startsWith(home) && command.isRegularFile()) {
            "${server.name}'s archive has no ${server.command}."
        }
        command.toFile().setExecutable(true)
        return JvmAgentLauncher.launch(
            server.copy(command = command.toString(), archive = null),
            workingDirectory,
            onLine,
            onExit,
        )
    }

    /** Where [archive] is unpacked for [agentId]; it exists once the agent has started here. */
    fun home(agentId: String, archive: AgentArchive): Path =
        PathManager.getSystemDir().resolve("tennyo-agents")
            .resolve(agentId.replace(UNSAFE_NAME, "_"))
            .resolve(archive.version.ifBlank { "current" }.replace(UNSAFE_NAME, "_"))

    private fun unpacked(agentId: String, archive: AgentArchive): Path {
        val home = home(agentId, archive)
        if (home.exists()) return home
        val download = Files.createTempFile("tennyo-agent", archive.url.substringAfterLast('/'))
        val staging = home.resolveSibling("${home.name}.partial")
        try {
            LOG.info("Downloading $agentId from ${archive.url}")
            // Bounded, so a stalled download fails the phone's start instead of keeping it waiting.
            val response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT)
                .build()
                .send(
                    HttpRequest.newBuilder(URI(archive.url)).timeout(DOWNLOAD_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofFile(download),
                )
            check(response.statusCode() in HTTP_OK) { "Downloading ${archive.url} answered ${response.statusCode()}." }
            archive.sha256?.let { expected ->
                val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(download))
                    .joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) { "${archive.url} does not match its sha256." }
            }
            NioFiles.deleteRecursively(staging)
            Files.createDirectories(staging)
            val zip = archive.url.endsWith(".zip")
            (if (zip) Decompressor.Zip(download) else Decompressor.Tar(download))
                // Creating a symlink needs a privilege Windows users rarely hold; the agents' own files are regular.
                .errorHandler { entry, _ ->
                    if (entry.type == Decompressor.Entry.Type.SYMLINK) SKIP else BAIL_OUT
                }
                .extract(staging)
            Files.move(staging, home)
            LOG.info("Unpacked $agentId into $home")
            return home
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // Logged here, then rethrown.
            LOG.warn("Could not install $agentId from ${archive.url}", failure)
            runCatching { NioFiles.deleteRecursively(staging) }
            throw failure
        } finally {
            Files.deleteIfExists(download)
        }
    }

    private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
    private val DOWNLOAD_CONNECT_TIMEOUT = Duration.ofSeconds(30)
    private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(10)
    private val HTTP_OK = 200..299
}

private val LOG = Logger.getInstance("app.tennyo.host.intellij.IntelliJAgents")
private object ReportLock
private var lastReport: String? = null
