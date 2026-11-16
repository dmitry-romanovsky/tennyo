package app.tennyo.mobile.design.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

internal enum class ScreenOrientation {
    Portrait,
    Landscape,
}

@Composable
internal fun rememberScreenOrientation(): ScreenOrientation {
    val size = LocalWindowInfo.current.containerSize
    return if (size.width > 0 && size.height > 0 && size.width > size.height) {
        ScreenOrientation.Landscape
    } else {
        ScreenOrientation.Portrait
    }
}

@Composable
fun isScreenLandscape(): Boolean = rememberScreenOrientation() == ScreenOrientation.Landscape
