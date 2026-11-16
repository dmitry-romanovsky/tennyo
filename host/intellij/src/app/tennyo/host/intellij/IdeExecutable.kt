package app.tennyo.host.intellij

import java.io.File
import kotlin.jvm.optionals.getOrNull

/** The running IDE's own executable, for the connection-help section's "allow this app through the firewall" copy. */
internal fun currentExecutablePath(): String = ProcessHandle.current().info().command().getOrNull() ?: ""

/**
 * The IDE's app name as the user sees it in their firewall dialog. On macOS that is the bundle name in
 * `/Applications/IntelliJ IDEA.app/Contents/MacOS/idea`; elsewhere the executable's own file name.
 */
internal fun currentAppName(): String {
    val path = currentExecutablePath()
    if (path.isEmpty()) return ""
    val bundleEnd = path.indexOf(APP_BUNDLE_SUFFIX)
    if (bundleEnd < 0) return File(path).name
    val bundleStart = path.lastIndexOf('/', bundleEnd) + 1
    return path.substring(bundleStart, bundleEnd)
}

private const val APP_BUNDLE_SUFFIX = ".app"
