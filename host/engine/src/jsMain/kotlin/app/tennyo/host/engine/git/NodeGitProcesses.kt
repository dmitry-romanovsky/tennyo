package app.tennyo.host.engine.git

import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.asByteArray
import app.tennyo.host.engine.platform.asUint8Array
import app.tennyo.host.engine.platform.jsOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `git` as a child process of the extension host. Cancelling the caller kills it together with anything it started,
 * such as `git-remote-https` or `ssh`.
 */
object NodeGitProcesses : GitProcesses {
    private val childProcess: dynamic = js("require('node:child_process')")
    private val buffer: dynamic = js("require('node:buffer').Buffer")
    private val windows = js("process.platform") == "win32"

    override suspend fun run(
        directory: String,
        arguments: List<String>,
        environment: Map<String, String>,
        input: ByteArray?,
        outputLimit: Int,
    ): GitProcessResult = suspendCancellableCoroutine { continuation ->
        val variables = js("Object.assign({}, process.env)")
        environment.forEach { (name, value) -> variables[name] = value }
        val child = childProcess.spawn(
            "git",
            arguments.toTypedArray(),
            jsOptions("cwd" to directory, "env" to variables, "windowsHide" to true),
        )
        val stdout = Collected(outputLimit)
        val stderr = Collected(MAX_STDERR_BYTES)
        child.stdout.on("data") { chunk: Uint8Array -> if (!stdout.add(chunk)) kill(child) }
        child.stderr.on("data") { chunk: Uint8Array -> stderr.add(chunk) }
        // Writing to a git that already exited fails on stdin; unhandled, that error would take the extension host.
        child.stdin.on("error") { _: dynamic -> }
        child.on("error") { error: dynamic ->
            if (continuation.isActive) {
                continuation.resumeWithException(GitLaunchException("git could not be started: ${error?.message}"))
            }
        }
        child.on("close") { code: dynamic ->
            if (continuation.isActive) {
                continuation.resume(
                    GitProcessResult(
                        exitCode = (code as? Int) ?: -1,
                        stdout = stdout.bytes(),
                        stderr = stderr.bytes().decodeToString(),
                        truncated = stdout.overflowed,
                    ),
                )
            }
        }
        continuation.invokeOnCancellation { kill(child) }
        if (input != null) child.stdin.end(buffer.from(input.asUint8Array())) else child.stdin.end()
    }

    override suspend fun readFile(path: String, limit: Int): ByteArray? {
        val stats = runCatching { NodeFs.lstatSync(path) }.getOrNull() ?: return null
        if (stats.isSymbolicLink()) return runCatching { NodeFs.readlinkSync(path).encodeToByteArray() }.getOrNull()
        if (stats.isDirectory()) return null
        // Past the limit the content is never shown; its size alone says so.
        if (stats.size.toDouble() > limit) return ByteArray(limit + 1)
        return runCatching { NodeFs.readFileSync(path).asByteArray() }.getOrNull()
    }

    /** Through `taskkill /T` on Windows, where `kill` would end only `git.exe` and leave its helpers running. */
    private fun kill(child: dynamic) {
        if (windows && child.pid != null) {
            val tree = arrayOf("/pid", "${child.pid}", "/T", "/F")
            childProcess.spawn("taskkill", tree, jsOptions("windowsHide" to true))
                .on("error") { _: dynamic -> child.kill() }
        } else {
            child.kill("SIGKILL")
        }
    }

    /** One output stream, kept up to `limit + 1` bytes and dropped past that. */
    private class Collected(private val limit: Int) {
        private val chunks = mutableListOf<Uint8Array>()
        private var size = 0
        var overflowed = false
            private set

        /** Keeps [chunk]; `false` once the stream has gone past its limit. */
        fun add(chunk: Uint8Array): Boolean {
            if (overflowed) return false
            chunks += chunk
            size += chunk.length
            overflowed = size > limit
            return !overflowed
        }

        fun bytes(): ByteArray =
            buffer.concat(chunks.toTypedArray()).unsafeCast<Uint8Array>().asByteArray().let { bytes ->
                if (bytes.size > limit + 1) bytes.copyOf(limit + 1) else bytes
            }
    }
}

private const val MAX_STDERR_BYTES = 64_000
