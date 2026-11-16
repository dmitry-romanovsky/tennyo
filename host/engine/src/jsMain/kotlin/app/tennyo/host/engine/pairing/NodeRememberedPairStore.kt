package app.tennyo.host.engine.pairing

import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodeOsPaths
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.asText
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.rpc.hostJson
import kotlinx.serialization.Serializable

/**
 * Remembered pairs in a file, for a host with no credential store of its own.
 *
 * The records are not encrypted, and deliberately so: a key kept beside the ciphertext protects nothing. What
 * protects them is that only this user can read the file — `0600` inside a `0700` directory, under `%LOCALAPPDATA%`
 * on Windows and the XDG data directory elsewhere, neither of which is backed up off the machine. A host that does
 * have a real secret store (VS Code's `SecretStorage`, IntelliJ's PasswordSafe) should use that instead of this.
 */
class NodeRememberedPairStore(
    private val directory: String = defaultLinkStateDirectory(),
    private val fileName: String = "remembered-pairs.json",
) : PersistentPairStore() {
    private val path: String get() = NodePath.join(directory, fileName)

    override fun load(): List<RememberedPairRecord> {
        val text = runCatching { NodeFs.readFileSync(path).asText() }.getOrNull() ?: return emptyList()
        return runCatching { HostJson.decodeFromString<RememberedPairFile>(text).records }
            .onFailure { LOG.warn("Unable to read the remembered pair file; starting from none.") }
            .getOrDefault(emptyList())
    }

    override fun store(records: List<RememberedPairRecord>) {
        runCatching {
            NodeFs.mkdirSync(directory, jsOptions("recursive" to true, "mode" to OWNER_ONLY_DIRECTORY))
            NodeFs.writeFileSync(
                path,
                hostJson(RememberedPairFile(records)),
                jsOptions("encoding" to "utf8", "mode" to OWNER_ONLY_FILE),
            )
        }.onFailure { LOG.warn("Unable to write the remembered pair file; pairs will not survive a restart.") }
    }

    private companion object {
        /** `0700` and `0600`, written in octal the way the mode is documented. */
        const val OWNER_ONLY_DIRECTORY = 448
        const val OWNER_ONLY_FILE = 384
        val LOG = HostLogger("NodeRememberedPairStore")
    }
}

/**
 * Where a Node host keeps its link state: `%LOCALAPPDATA%` on Windows, `$XDG_DATA_HOME` or `~/.local/share` on
 * everything else. Both are per-user and outside the roaming profile, so the records never leave the machine.
 */
fun defaultLinkStateDirectory(): String {
    val windowsLocal = processEnv("LOCALAPPDATA")
    if (windowsLocal != null) return NodePath.join(windowsLocal, APPLICATION_DIRECTORY)
    val xdgData = processEnv("XDG_DATA_HOME") ?: NodePath.join(NodeOsPaths.homedir(), ".local/share")
    return NodePath.join(xdgData, APPLICATION_DIRECTORY)
}

private const val APPLICATION_DIRECTORY = "tennyo"

private fun processEnv(name: String): String? =
    (js("process").env[name] as? String)?.takeIf { it.isNotBlank() }

@Serializable
private data class RememberedPairFile(val records: List<RememberedPairRecord> = emptyList())
