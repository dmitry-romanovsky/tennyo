package app.tennyo.host.vscode

import app.tennyo.host.vscode.api.LogOutputChannel
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/** Routes engine logging into the extension's log channel, where the editor applies the user's log level. */
internal class VsCodeLogWriter(private val channel: LogOutputChannel) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val prefix = tag.takeIf { it.isNotEmpty() }?.let { "[$it]" }
        val text = listOfNotNull(prefix, message, throwable?.stackTraceToString()).joinToString(" ")
        when (severity) {
            Severity.Verbose -> channel.trace(text)
            Severity.Debug -> channel.debug(text)
            Severity.Info -> channel.info(text)
            Severity.Warn -> channel.warn(text)
            Severity.Error, Severity.Assert -> channel.error(text)
        }
    }
}
