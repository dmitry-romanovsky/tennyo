package app.tennyo.host.intellij

import app.tennyo.host.engine.panes.FileSnapshot
import app.tennyo.host.engine.panes.IdeFilesPort
import app.tennyo.host.engine.rpc.utf8SizeExceeds
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path

/** Project files through `java.nio` for the tree and through the VFS and open documents for content. */
internal class IntelliJFilesPort(private val project: Project) : IdeFilesPort {
    override fun rootPath(): String? =
        project.basePath?.let { basePath ->
            runCatching { Path.of(basePath).toRealPath() }.getOrNull()
        }?.companionPath()

    override fun realPath(path: String): String? =
        runCatching { Path.of(path).toRealPath() }.getOrNull()?.companionPath()

    override fun resolve(base: String, relative: String): String =
        Path.of(base).resolve(relative).normalize().companionPath()

    override fun isDirectory(path: String): Boolean = Files.isDirectory(Path.of(path))

    override fun forEachChild(directory: String, visit: (String) -> Boolean) {
        Files.list(Path.of(directory)).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (!visit(iterator.next().companionPath())) break
            }
        }
    }

    override fun read(path: String, byteLimit: Int): FileSnapshot {
        val nioPath = Path.of(path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
        unsupportedSnapshot(virtualFile)?.let { return it }
        return remoteFileSnapshot(nioPath, virtualFile, byteLimit)
    }

    override fun write(path: String, text: String, byteLimit: Int): FileSnapshot {
        val nioPath = Path.of(path)
        var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
        unsupportedSnapshot(virtualFile)?.let { return it }
        if (virtualFile == null) {
            Files.writeString(nioPath, text)
            virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
        } else {
            val document = runReadActionBlocking {
                FileDocumentManager.getInstance().getDocument(virtualFile)
            }
            if (document != null) {
                invokeOnEdtAndWait {
                    WriteCommandAction.writeCommandAction(project)
                        .withName(HostBundle.message("file.edit.command.name"))
                        .run<RuntimeException> {
                            if (document.text != text) {
                                document.setText(text)
                            }
                            FileDocumentManager.getInstance().saveDocument(document)
                        }
                }
            } else {
                Files.writeString(nioPath, text)
                virtualFile.refresh(false, false)
            }
        }
        return remoteFileSnapshot(nioPath, virtualFile, byteLimit)
    }

    /** A file the IDE knows as binary or a directory is answered without reading or writing it. */
    private fun unsupportedSnapshot(virtualFile: VirtualFile?): FileSnapshot? =
        virtualFile
            ?.let { runReadActionBlocking { it.unsupportedContentReason() } }
            ?.let { reason ->
                FileSnapshot(text = "", revision = null, writable = false, viewable = false, unavailableReason = reason)
            }
}

private fun remoteFileSnapshot(path: Path, virtualFile: VirtualFile?, byteLimit: Int): FileSnapshot {
    if (runCatching { Files.size(path) > byteLimit }.getOrDefault(true)) {
        return oversizedFileSnapshot(virtualFile)
    }
    if (virtualFile == null) {
        return readSnapshotFromPath(path)
    }
    return runReadActionBlocking {
        val unsupportedReason = virtualFile.unsupportedContentReason()
        if (unsupportedReason != null) {
            return@runReadActionBlocking FileSnapshot(
                text = "",
                revision = virtualFile.modificationStamp.toString(),
                writable = false,
                viewable = false,
                unavailableReason = unsupportedReason,
            )
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        if (document == null) {
            return@runReadActionBlocking runCatching {
                FileSnapshot(
                    text = Files.readString(path),
                    revision = virtualFile.modificationStamp.toString(),
                    writable = virtualFile.isWritable,
                )
            }.getOrElse {
                FileSnapshot(
                    text = "",
                    revision = virtualFile.modificationStamp.toString(),
                    writable = false,
                    viewable = false,
                    unavailableReason = "This file is not text and can't be shown on mobile.",
                )
            }
        }
        val exceedsLimit = document.text.utf8SizeExceeds(byteLimit)
        FileSnapshot(
            text = document.text.takeUnless { exceedsLimit }.orEmpty(),
            revision = document.modificationStamp.toString(),
            writable = virtualFile.isWritable,
            viewable = !exceedsLimit,
            unavailableReason = if (exceedsLimit) "This file exceeds the mobile viewing limit." else null,
        )
    }
}

private fun oversizedFileSnapshot(virtualFile: VirtualFile?): FileSnapshot =
    FileSnapshot(
        text = "",
        revision = virtualFile?.modificationStamp?.toString(),
        writable = false,
        viewable = false,
        unavailableReason = "This file exceeds the mobile viewing limit.",
    )

private fun readSnapshotFromPath(path: Path): FileSnapshot =
    try {
        FileSnapshot(
            text = Files.readString(path),
            revision = null,
            writable = Files.isWritable(path),
        )
    } catch (_: MalformedInputException) {
        FileSnapshot(
            text = "",
            revision = null,
            writable = Files.isWritable(path),
            viewable = false,
            unavailableReason = "This file is not text and can't be shown on mobile.",
        )
    } catch (_: Exception) {
        FileSnapshot(
            text = "",
            revision = null,
            writable = false,
            viewable = false,
            unavailableReason = "This content can't be shown on mobile.",
        )
    }

internal fun VirtualFile.unsupportedContentReason(): String? =
    when {
        isDirectory -> "Directories can't be shown in the mobile editor."
        fileType.isBinary -> "This file is binary and can't be shown on mobile."
        else -> null
    }

/** A path in the core's form: absolute, normalised, and `/`-separated on every OS. */
internal fun Path.companionPath(): String =
    toString().let { if (File.separatorChar == '\\') it.replace('\\', '/') else it }

/** The open file's path in the core's form, or `null` for a file outside the local file system. */
internal fun VirtualFile.companionNioPath(): String? =
    runCatching { toNioPath().normalize().companionPath() }.getOrNull()
