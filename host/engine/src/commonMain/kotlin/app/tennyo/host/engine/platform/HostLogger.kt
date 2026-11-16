package app.tennyo.host.engine.platform

import co.touchlab.kermit.Logger

/**
 * Tagged logging for core code, written through Kermit. Each IDE adapter installs the log writer that decides where the
 * lines go and whether DEBUG lines are kept, so core code never depends on an IDE logger.
 */
class HostLogger(tag: String) {
    private val logger = Logger.withTag(tag)

    /** Verbose diagnostics; the message is only built when the adapter keeps DEBUG lines. */
    fun debug(message: () -> String) = logger.d(message = message)

    fun info(message: String, throwable: Throwable? = null) = logger.i(message, throwable)

    fun warn(message: String, throwable: Throwable? = null) = logger.w(message, throwable)
}

private const val DISCOVERY_DEBUG_PREFIX = "[discovery] "

/** Discovery diagnostics carry a shared prefix so a debug log can be filtered to the discovery flow. */
fun HostLogger.discoveryDebug(message: () -> String) = debug { DISCOVERY_DEBUG_PREFIX + message() }

private const val DEBUG_SHORT_ID_LENGTH = 8

fun String.debugShortId(): String = if (length <= DEBUG_SHORT_ID_LENGTH) this else take(DEBUG_SHORT_ID_LENGTH)

fun String?.debugShortIdOrNone(): String = this?.debugShortId() ?: "none"

fun String?.debugQuotedOrNone(): String = this?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "none"
