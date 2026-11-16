package app.tennyo.mobile.feature.local

/** How a new workspace is laid out. Python comes first: it is what runs on this device. */
data class WorkspaceTemplate(
    val kind: WorkspaceKind = WorkspaceKind.PYTHON_SCRIPT,
    val createGitRepository: Boolean = true,
    /** A readme saying what the project is and how to run it here. */
    val addReadme: Boolean = false,
)

enum class WorkspaceKind {
    /** One `main.py`, run with the Run button. */
    PYTHON_SCRIPT,

    /** An importable package with `__main__`, a `pyproject.toml` and a test. */
    PYTHON_PACKAGE,

    EMPTY,
}

/** The starter files of [template] for a project called [projectName], by path relative to the workspace root. */
internal fun WorkspaceTemplate.files(projectName: String): Map<String, String> = buildMap {
    val module = pythonModuleName(projectName)
    when (kind) {
        WorkspaceKind.PYTHON_SCRIPT -> {
            put("main.py", PYTHON_MAIN)
            put("requirements.txt", REQUIREMENTS)
        }

        WorkspaceKind.PYTHON_PACKAGE -> {
            put("pyproject.toml", pyproject(module))
            put("$module/__init__.py", PACKAGE_INIT)
            put("$module/__main__.py", packageMain(module))
            put("tests/test_$module.py", packageTest(module))
        }

        WorkspaceKind.EMPTY -> Unit
    }
    if (addReadme) put("README.md", readme(projectName, kind, module))
    if (createGitRepository) put(".gitignore", PYTHON_GITIGNORE)
}

/** [projectName] as a Python identifier: lower case, words joined by `_`, never starting with a digit. */
internal fun pythonModuleName(projectName: String): String {
    val words = projectName.lowercase().split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
    val joined = words.joinToString("_").ifEmpty { "app" }
    return if (joined.first().isDigit()) "app_$joined" else joined
}

private const val PYTHON_MAIN = """def greet(name: str) -> str:
    return f"Hello, {name}!"


if __name__ == "__main__":
    print(greet("world"))
"""

private const val REQUIREMENTS = """# One package per line, e.g. requests==2.32.3
# Packages a script imports are loaded when it runs on this device.
"""

private const val PACKAGE_INIT = """def greet(name: str) -> str:
    return f"Hello, {name}!"
"""

private fun packageMain(module: String) = """from $module import greet


def main():
    print(greet("world"))


if __name__ == "__main__":
    main()
"""

private fun packageTest(module: String) = """from $module import greet


def test_greet():
    assert greet("Tennyo") == "Hello, Tennyo!"


if __name__ == "__main__":
    test_greet()
    print("ok")
"""

private fun pyproject(module: String) = """[project]
name = "${module.replace('_', '-')}"
version = "0.1.0"
requires-python = ">=3.10"

[project.scripts]
${module.replace('_', '-')} = "$module.__main__:main"
"""

private fun readme(projectName: String, kind: WorkspaceKind, module: String): String {
    val howToRun = when (kind) {
        WorkspaceKind.PYTHON_SCRIPT -> "Open `main.py` and tap Run. Output appears below the editor."
        WorkspaceKind.PYTHON_PACKAGE ->
            "Open `$module/__main__.py` and tap Run, or run `tests/test_$module.py` to check it."
        WorkspaceKind.EMPTY -> "Add files from the Files drawer. A `.py` file can be run with the Run button."
    }
    return """# $projectName

This project was created in Tennyo and lives on this device.

## Run

$howToRun

Commit your changes from the Git pane.
"""
}

private const val PYTHON_GITIGNORE = """__pycache__/
*.py[cod]
.venv/
.pytest_cache/
dist/
*.egg-info/
"""

