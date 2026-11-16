package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalTab

internal fun previewTerminalState(): TerminalState =
    TerminalState(
        tabs = listOf(
            TerminalTab(
                id = "terminal-main",
                title = "Terminal",
                cwd = "/projects/example",
                isSelected = true,
                snapshot = TerminalSnapshot(
                    text = "$ ./gradlew :app:shared:testAndroidHostTest\nBUILD SUCCESSFUL in 18s\n$ ",
                    cursorOffset = 69,
                ),
            ),
            TerminalTab(
                id = "terminal-ios",
                title = "iOS simulator",
                cwd = "/projects/example",
                snapshot = TerminalSnapshot(text = "$ xcodebuild -showsdks\n"),
            ),
        ),
        selectedTerminalId = "terminal-main",
    )
