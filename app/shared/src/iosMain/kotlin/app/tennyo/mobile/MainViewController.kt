package app.tennyo.mobile

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.tennyo.mobile.di.appModule
import app.tennyo.mobile.di.iosPlatformModule
import app.tennyo.mobile.di.linkModule
import app.tennyo.mobile.link.LinkDiagnostics
import app.tennyo.mobile.root.RootComponent
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

// Swift calls this as a view controller factory, so it keeps the type-like name the iOS app expects.
@Suppress("FunctionNaming")
fun MainViewController(
    allowUnsafeTraffic: Boolean = false,
): UIViewController =
    ComposeUIViewController {
        val lifecycle = remember { LifecycleRegistry() }
        val rootComponent = remember {
            appKoin(allowUnsafeTraffic).get<RootComponent> {
                parametersOf(DefaultComponentContext(lifecycle))
            }
        }
        DisposableEffect(lifecycle) {
            lifecycle.resume()
            onDispose { lifecycle.destroy() }
        }
        TennyoApp(rootComponent = rootComponent)
    }

private var startedKoin: Koin? = null

/** Koin starts once per process; a later view controller reuses the graph the first one started. */
@OptIn(ExperimentalNativeApi::class)
private fun appKoin(allowUnsafeTraffic: Boolean): Koin =
    startedKoin ?: run {
        LinkDiagnostics.verboseLogging = Platform.isDebugBinary
        startKoin { modules(iosPlatformModule(allowUnsafeTraffic), linkModule, appModule) }.koin
            .also { startedKoin = it }
    }
