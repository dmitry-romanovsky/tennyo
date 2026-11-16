package app.tennyo.host.intellij

import com.intellij.openapi.diagnostic.Logger

enum class HostLogLevel {
    DEBUG,
    INFO,
}

object HostLoggingPolicy {
    val defaultLogLevel: HostLogLevel
        get() = HostBuildInfo.defaultLogLevel

    fun allowsVerboseDiagnostics(logLevel: HostLogLevel = defaultLogLevel): Boolean =
        logLevel == HostLogLevel.DEBUG

    inline fun debug(
        logger: Logger,
        message: () -> String,
    ) {
        if (!allowsVerboseDiagnostics()) return
        logger.debug(message())
    }
}
