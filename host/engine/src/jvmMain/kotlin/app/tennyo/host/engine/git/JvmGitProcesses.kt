package app.tennyo.host.engine.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * `git` as a JVM process. Its output is drained on pool threads while the caller waits for the exit without blocking,
 * so cancelling the caller kills the process — with anything it started, such as `git-remote-https` or `ssh` — at
 * once instead of waiting on a pipe.
 */
object JvmGitProcesses : GitProcesses {
    override suspend fun run(
        directory: String,
        arguments: List<String>,
        environment: Map<String, String>,
        input: ByteArray?,
        outputLimit: Int,
    ): GitProcessResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf("git") + arguments)
                .directory(File(directory))
                .apply { environment().putAll(environment) }
                .start()
        } catch (failure: IOException) {
            throw GitLaunchException("git could not be started in $directory", failure)
        }
        try {
            val stdout = drain(process.inputStream, outputLimit) { process.killTree() }
            val stderr = drain(process.errorStream, MAX_STDERR_BYTES) {}
            process.outputStream.use { stream -> input?.let(stream::write) }
            val exitCode = process.onExit().await().exitValue()
            val output = stdout.await()
            GitProcessResult(
                exitCode = exitCode,
                stdout = output,
                stderr = stderr.await().decodeToString(),
                truncated = output.size > outputLimit,
            )
        } finally {
            if (process.isAlive) process.killTree()
        }
    }

    override suspend fun readFile(path: String, limit: Int): ByteArray? = withContext(Dispatchers.IO) {
        val link = runCatching { File(path).toPath() }.getOrNull() ?: return@withContext null
        if (Files.isSymbolicLink(link)) {
            return@withContext runCatching { Files.readSymbolicLink(link).toString().encodeToByteArray() }.getOrNull()
        }
        File(path).takeIf(File::isFile)?.let { file ->
            runCatching { file.inputStream().use { it.readNBytes(limit + 1) } }.getOrNull()
        }
    }

    /**
     * Reads [stream] to its end on a pool thread, keeping its first `limit + 1` bytes: past [limit] it calls
     * [onOverflow] once and discards the rest, so the process never stalls on a full pipe.
     */
    private fun drain(stream: InputStream, limit: Int, onOverflow: () -> Unit): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync(
            {
                stream.use {
                    val kept = it.readNBytes(limit + 1)
                    if (kept.size > limit) {
                        onOverflow()
                        it.transferTo(OutputStream.nullOutputStream())
                    }
                    kept
                }
            },
            Dispatchers.IO.asExecutor(),
        )

    private fun Process.killTree() {
        descendants().forEach(ProcessHandle::destroyForcibly)
        destroyForcibly()
    }
}

private const val MAX_STDERR_BYTES = 64_000
