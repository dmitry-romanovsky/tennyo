package app.tennyo.host.engine.rpc

import app.tennyo.host.engine.panes.FakeProjectFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectPathSecurityTest {
    private val projectRoot = "/work/project"

    @Test
    fun `project path rejects a symbolic link that escapes the project`() {
        val files = FakeProjectFiles(projectRoot)
            .directory(projectRoot)
            .file("/work/external/secret.txt", "secret")
            .link("$projectRoot/external-link", "/work/external")

        assertNull(files.resolveExistingProjectPath(projectRoot, "external-link/secret.txt"))
    }

    @Test
    fun `project path accepts a symbolic link whose target stays in the project`() {
        val files = FakeProjectFiles(projectRoot)
            .file("$projectRoot/source/Main.kt", "fun main() = Unit")
            .link("$projectRoot/source-link", "source")

        assertEquals(
            "$projectRoot/source/Main.kt",
            files.resolveExistingProjectPath(projectRoot, "source-link/Main.kt"),
        )
    }

    @Test
    fun `project path rejects parent traversal`() {
        val files = FakeProjectFiles(projectRoot)
            .directory(projectRoot)
            .file("/work/secret.txt", "secret")

        assertNull(files.resolveExistingProjectPath(projectRoot, "../secret.txt"))
        assertNull(files.resolveExistingProjectPath(projectRoot, "source/../../secret.txt"))
    }

    @Test
    fun `project path rejects a sibling that only shares the root as a prefix`() {
        val files = FakeProjectFiles(projectRoot)
            .directory(projectRoot)
            .file("/work/project-private/secret.txt", "secret")

        assertNull(files.resolveExistingProjectPath(projectRoot, "../project-private/secret.txt"))
        assertNull(files.resolveExistingProjectPath(projectRoot, "/work/project-private/secret.txt"))
    }

    @Test
    fun `project path rejects an absolute path outside the project and a missing file`() {
        val files = FakeProjectFiles(projectRoot)
            .file("$projectRoot/README.md")
            .file("/etc/passwd")

        assertNull(files.resolveExistingProjectPath(projectRoot, "/etc/passwd"))
        assertNull(files.resolveExistingProjectPath(projectRoot, "missing.txt"))
        assertEquals("$projectRoot/README.md", files.resolveExistingProjectPath(projectRoot, "README.md"))
    }

    @Test
    fun `tree lists only children that stay in the project`() {
        val files = FakeProjectFiles(projectRoot)
            .file("$projectRoot/README.md")
            .file("/work/external/secret.txt")
            .link("$projectRoot/external-link", "/work/external")

        val tree = files.fileTree("project", null).root

        assertEquals(listOf("README.md"), tree.children.map { it.path })
    }
}
