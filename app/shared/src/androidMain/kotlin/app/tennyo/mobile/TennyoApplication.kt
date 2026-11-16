package app.tennyo.mobile

import android.app.Application
import app.tennyo.mobile.di.androidPlatformModule
import app.tennyo.mobile.di.appModule
import app.tennyo.mobile.di.linkModule
import app.tennyo.mobile.link.LinkDiagnostics
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module

open class TennyoApplication : Application() {
    /** What the installable shell adds: bindings that need plugins only an application module can apply. */
    protected open val shellModules: List<Module> = emptyList()

    override fun onCreate() {
        super.onCreate()
        LinkDiagnostics.verboseLogging = AndroidBuildConfig.isDebug
        startKoin {
            androidContext(this@TennyoApplication)
            modules(listOf(androidPlatformModule, linkModule, appModule) + shellModules)
        }
    }
}
