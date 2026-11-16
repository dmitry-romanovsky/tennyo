package app.tennyo.host.intellij

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture

internal fun <T> invokeOnEdtAndWait(action: () -> T): T {
    if (ApplicationManager.getApplication().isDispatchThread) {
        return action()
    }
    val result = CompletableFuture<T>()
    ApplicationManager.getApplication().invokeAndWait {
        runCatching(action)
            .onSuccess { result.complete(it) }
            .onFailure { result.completeExceptionally(it) }
    }
    return result.get()
}

private fun String.replacePrefix(prefix: String, replacement: String): String =
    if (startsWith(prefix)) replacement + removePrefix(prefix) else this

internal fun shortenProjectHomePath(path: String, userHome: String? = System.getProperty("user.home")): String {
    if (path.isBlank()) return path
    if (userHome.isNullOrBlank()) return path
    val (myPath, myHome) = path.replace("\\", "/") to userHome.replace("\\", "/")
    return when {
        myPath.startsWith("$myHome/") -> myPath.replacePrefix(myHome, "~")
        else -> myPath
    }
}
