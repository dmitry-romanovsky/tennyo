package app.tennyo.mobile.feature.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What each kind of new workspace starts with. */
class WorkspaceTemplateTest {
    @Test
    fun `a new workspace is a Python script by default`() {
        val files = WorkspaceTemplate().files("demo")

        assertEquals(setOf("main.py", "requirements.txt", ".gitignore"), files.keys)
        assertTrue("__main__" in files.getValue("main.py"))
        assertTrue("__pycache__/" in files.getValue(".gitignore"))
    }

    @Test
    fun `a Python package lays out an importable package with a test`() {
        val files = WorkspaceTemplate(kind = WorkspaceKind.PYTHON_PACKAGE).files("My Tool")

        assertEquals(
            setOf("pyproject.toml", "my_tool/__init__.py", "my_tool/__main__.py", "tests/test_my_tool.py", ".gitignore"),
            files.keys,
        )
        assertTrue("name = \"my-tool\"" in files.getValue("pyproject.toml"))
        assertTrue("from my_tool import greet" in files.getValue("tests/test_my_tool.py"))
    }

    @Test
    fun `the readme explains how to run the project here`() {
        val readme = WorkspaceTemplate(addReadme = true).files("demo").getValue("README.md")

        assertTrue("Run" in readme)
    }

    @Test
    fun `module names are Python identifiers`() {
        assertEquals("my_tool", pythonModuleName("My Tool"))
        assertEquals("app_2048", pythonModuleName("2048"))
        assertEquals("app", pythonModuleName("---"))
    }

    @Test
    fun `an empty workspace has nothing but what was asked for`() {
        assertEquals(emptySet(), WorkspaceTemplate(kind = WorkspaceKind.EMPTY, createGitRepository = false).files("x").keys)
    }
}
