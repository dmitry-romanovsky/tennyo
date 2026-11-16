package app.tennyo.host.vscode.panes

import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.vscode.api.VsCodeEnv

/**
 * Pseudo-terminals from the editor's own `node-pty`, the native module every VS Code build ships for its terminals:
 * inside `node_modules.asar` (VS Code, Cursor) or `node_modules` (Devin Desktop). An extension cannot bundle a native
 * module for every editor's Electron, so a build that hides it has no terminal for Tennyo.
 */
internal object EditorPty {
    private val module: dynamic by lazy(::load)

    val isAvailable: Boolean get() = module != null

    /** Starts [file] with [args] in [cwd] on a pseudo-terminal of [columns] by [rows]; `null` without `node-pty`. */
    fun spawn(file: String, args: List<String>, cwd: String, columns: Int, rows: Int): dynamic {
        val pty = module ?: return null
        val options = js("{}")
        options.name = "xterm-256color"
        options.cols = columns
        options.rows = rows
        options.cwd = cwd
        options.env = js("Object.assign({}, process.env)")
        return pty.spawn(file, args.toTypedArray(), options)
    }

    /** The shell a new terminal runs: PowerShell on Windows, the user's login shell elsewhere. */
    fun defaultShell(): List<String> =
        if (isWindows()) {
            listOf("powershell.exe", "-NoLogo")
        } else {
            listOf(js("process.env.SHELL") as String? ?: "/bin/bash")
        }

    /** The command line [commandLine] run by the default shell, as a task's shell execution runs it. */
    fun shellCommand(commandLine: String): List<String> =
        if (isWindows()) {
            listOf("powershell.exe", "-NoLogo", "-NoProfile", "-Command", commandLine)
        } else {
            listOf(js("process.env.SHELL") as String? ?: "/bin/sh", "-c", commandLine)
        }

    private fun isWindows(): Boolean = js("process.platform") == "win32"

    private fun load(): dynamic {
        val path = js("require('node:path')")
        for (folder in listOf("node_modules.asar", "node_modules")) {
            val loaded = runCatching { js("require")(path.join(VsCodeEnv.appRoot, folder, "node-pty")) }.getOrNull()
            if (loaded != null) return loaded
        }
        LOG.info("The editor ships no node-pty Tennyo can load; terminals and task output stay on the computer.")
        return null
    }

    private val LOG = HostLogger("EditorPty")
}
