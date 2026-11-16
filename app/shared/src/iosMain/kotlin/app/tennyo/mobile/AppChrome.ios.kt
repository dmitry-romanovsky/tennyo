package app.tennyo.mobile

import androidx.compose.runtime.Composable
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.root.RootComponent
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation

/** UIKit takes the status bar style from the hosting view controller, which follows the system appearance. */
@Composable
internal actual fun PlatformSystemBars(themeMode: AppThemeMode) = Unit

internal actual fun rootStackAnimation(rootComponent: RootComponent): StackAnimation<Any, RootComponent.Child>? = null
