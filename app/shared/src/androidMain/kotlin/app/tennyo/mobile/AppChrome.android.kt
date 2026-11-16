package app.tennyo.mobile

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.resolveAppTheme
import app.tennyo.mobile.root.RootComponent
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation

@Composable
@Suppress("DEPRECATION")
internal actual fun PlatformSystemBars(themeMode: AppThemeMode) {
    val resolvedTheme = themeMode.resolveAppTheme(systemDarkTheme = isSystemInDarkTheme())
    val view = LocalView.current
    val transparent = Color.Transparent.toArgb()

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = transparent
        window.navigationBarColor = transparent
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = !resolvedTheme.darkTheme
            isAppearanceLightNavigationBars = !resolvedTheme.darkTheme
        }
    }
}

@OptIn(ExperimentalDecomposeApi::class)
internal actual fun rootStackAnimation(rootComponent: RootComponent): StackAnimation<Any, RootComponent.Child>? =
    predictiveBackAnimation(
        backHandler = rootComponent.backHandler,
        fallbackAnimation = stackAnimation(),
        onBack = rootComponent::onBack,
    )
