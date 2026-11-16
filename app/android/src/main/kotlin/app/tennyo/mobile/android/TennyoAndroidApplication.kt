package app.tennyo.mobile.android

import app.tennyo.mobile.AndroidBuildConfig
import app.tennyo.mobile.TennyoApplication
import app.tennyo.mobile.feature.local.PythonRunner
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Pushes this build variant's [BuildConfig] values into [AndroidBuildConfig] before delegating to
 * [TennyoApplication.onCreate] — see [AndroidBuildConfig] for why this indirection exists.
 */
class TennyoAndroidApplication : TennyoApplication() {
    override fun onCreate() {
        AndroidBuildConfig.isDebug = BuildConfig.DEBUG
        AndroidBuildConfig.allowUnsafeTraffic = BuildConfig.TENNYO_ALLOW_UNSAFE_TRAFFIC
        AndroidBuildConfig.appVersionName = BuildConfig.VERSION_NAME
        super.onCreate()
    }

    override val shellModules = listOf(module { single<PythonRunner> { ChaquopyPythonRunner(androidContext()) } })
}
