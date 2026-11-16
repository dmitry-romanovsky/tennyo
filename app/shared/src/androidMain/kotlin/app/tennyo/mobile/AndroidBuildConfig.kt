package app.tennyo.mobile

/**
 * Build-variant-specific values. `:app:shared` is a `com.android.kotlin.multiplatform.library` module, which has
 * no build types and therefore no per-variant `BuildConfig` fields — so `:app:android` (which
 * still has debug/release build types) pushes these in via [TennyoAndroidApplication] before any
 * other app code runs. Defaults here match release/production behavior.
 */
object AndroidBuildConfig {
    var isDebug: Boolean = false
    var allowUnsafeTraffic: Boolean = false
    var appVersionName: String = ""
}
