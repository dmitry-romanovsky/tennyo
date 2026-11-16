package app.tennyo.mobile.feature.launch

import app.tennyo.contract.LaunchConfiguration

internal fun previewLaunchState(): LaunchState =
    LaunchState(
        configurations = listOf(
            LaunchConfiguration("android-app", "Android app", "Android", folder = "Mobile", isRunning = true),
            LaunchConfiguration("android-tests", "Android tests", "JUnit", folder = "Mobile"),
            LaunchConfiguration("ios-smoke", "iOS smoke", "Gradle", folder = "Mobile"),
            LaunchConfiguration("lint", "Lint", "Gradle"),
        ),
    )
