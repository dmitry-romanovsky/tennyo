package app.tennyo.host.intellij

import app.tennyo.host.engine.panes.EditorTabInfo
import app.tennyo.host.engine.panes.FileSnapshot
import app.tennyo.host.engine.panes.IdeEditorTabsPort
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.MalformedInputException
import java.nio.file.Path

/** The project's editor tabs through [FileEditorManager]; every call that touches it runs on the EDT. */
internal class IntelliJEditorTabsPort(private val project: Project) : IdeEditorTabsPort {
    override fun tabs(): List<EditorTabInfo> =
        invokeOnEdtAndWait {
            FileEditorManager.getInstance(project).openFiles
                .mapNotNull { file ->
                    file.companionNioPath()?.let { path ->
                        EditorTabInfo(path = path, name = file.name, writable = file.isWritable)
                    }
                }
        }

    override fun selectedPath(): String? =
        invokeOnEdtAndWait {
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.companionNioPath()
        }

    override fun open(path: String): Boolean {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return false
        invokeOnEdtAndWait {
            FileEditorManager.getInstance(project).openFile(file, true, true)
        }
        return true
    }

    override fun close(path: String): Boolean {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return false
        invokeOnEdtAndWait {
            FileEditorManager.getInstance(project).closeFile(file)
        }
        return true
    }

    override fun contentOf(path: String): FileSnapshot? =
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))?.editorSnapshot()

    private fun VirtualFile.editorSnapshot(): FileSnapshot =
        runReadActionBlocking {
            val unsupportedReason = unsupportedContentReason()
            if (unsupportedReason != null) {
                return@runReadActionBlocking FileSnapshot(
                    text = "",
                    revision = modificationStamp.toString(),
                    writable = false,
                    viewable = false,
                    unavailableReason = unsupportedReason,
                )
            }
            val document = FileDocumentManager.getInstance().getDocument(this)
            if (document != null) {
                FileSnapshot(
                    text = document.text,
                    revision = document.modificationStamp.toString(),
                    writable = isWritable,
                )
            } else {
                readTextSnapshot()
            }
        }

    private fun VirtualFile.readTextSnapshot(): FileSnapshot =
        try {
            FileSnapshot(
                text = VfsUtilCore.loadText(this),
                revision = modificationStamp.toString(),
                writable = isWritable,
            )
        } catch (_: MalformedInputException) {
            FileSnapshot(
                text = "",
                revision = modificationStamp.toString(),
                writable = false,
                viewable = false,
                unavailableReason = "This file is not text and can't be shown on mobile.",
            )
        } catch (_: Exception) {
            FileSnapshot(
                text = "",
                revision = modificationStamp.toString(),
                writable = false,
                viewable = false,
                unavailableReason = "This content can't be shown on mobile.",
            )
        }
}
