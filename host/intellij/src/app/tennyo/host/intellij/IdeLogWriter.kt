package app.tennyo.host.intellij

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import com.intellij.openapi.diagnostic.Logger

/**
 * Routes `:host:engine` logging into the IDE log. Each Kermit tag becomes its own logger category, and DEBUG lines
 * follow [HostLoggingPolicy] exactly as the plugin's own diagnostics do.
 */
internal class IdeLogWriter : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean =
        severity > Severity.Debug || HostLoggingPolicy.allowsVerboseDiagnostics()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val logger = Logger.getInstance("$ENGINE_LOG_CATEGORY.${tag.ifEmpty { "engine" }}")
        when (severity) {
            Severity.Verbose, Severity.Debug -> logger.debug(message, throwable)
            Severity.Info -> logger.info(message, throwable)
            Severity.Warn -> logger.warn(message, throwable)
            Severity.Error, Severity.Assert -> logger.error(message, throwable)
        }
    }

    private companion object {
        const val ENGINE_LOG_CATEGORY = "app.tennyo.host.engine"
    }
}
