package app.tennyo.mobile.ui

import platform.Foundation.NSBundle

internal actual val appVersionName: String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
