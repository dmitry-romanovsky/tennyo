package app.tennyo.mobile.feature.files

import app.tennyo.contract.FileNode

internal fun previewFilesState(): FilesState =
    FilesState(
        root = FileNode(
            path = "/projects/example",
            name = "Tennyo",
            isDirectory = true,
            children = listOf(
                FileNode("README.md", "README.md", isDirectory = false),
                FileNode(
                    path = "app",
                    name = "app",
                    isDirectory = true,
                    children = listOf(
                        FileNode("app/shared/src/commonMain/kotlin", "commonMain", isDirectory = true),
                        FileNode("app/android/src/main/kotlin", "android", isDirectory = true),
                    ),
                ),
            ),
        ),
        openedFiles = listOf(
            OpenedFile(
                path = "app/feature/terminal/src/commonMain/kotlin/TerminalPane.kt",
                name = "TerminalPane.kt",
                text = """
                    |@Composable
                    |fun TerminalPane() {
                    |    TerminalKeyBar(...)
                    |}
                """.trimMargin(),
                revision = "terminal-preview",
                writable = true,
                isDirty = true,
            ),
            OpenedFile(
                path = "app/feature/run/src/commonMain/kotlin/LaunchPane.kt",
                name = "LaunchPane.kt",
                text = "fun LaunchPane() = Unit\n",
                revision = "launch-preview",
                writable = true,
            ),
        ),
        history = mapOf("app/feature/terminal/src/commonMain/kotlin/TerminalPane.kt" to EditHistory(undo = listOf(""))),
    )
