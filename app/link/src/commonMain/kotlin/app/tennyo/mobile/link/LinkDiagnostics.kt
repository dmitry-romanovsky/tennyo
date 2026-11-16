package app.tennyo.mobile.link

import co.touchlab.kermit.Logger

@PublishedApi
internal const val DISCOVERY_LOG_TAG = "LinkDiscovery"

inline fun discoveryDebug(
    message: () -> String,
) {
    if (!LinkDiagnostics.verboseLogging) return
    Logger.d(messageString = message(), tag = DISCOVERY_LOG_TAG)
}

fun discoveryInfo(message: String) {
    Logger.i(messageString = message, tag = DISCOVERY_LOG_TAG)
}

fun discoveryWarn(message: String) {
    Logger.w(messageString = message, tag = DISCOVERY_LOG_TAG)
}

/** Diagnostics switches, set once at startup from the app build: debug builds trace discovery and pairing. */
object LinkDiagnostics {
    var verboseLogging: Boolean = false
}

/** Enough of an id to tell sessions apart in a log without writing the whole identifier. */
fun String.debugShortId(): String = take(DEBUG_ID_LENGTH)

private const val DEBUG_ID_LENGTH = 8

fun String?.debugShortIdOrNone(): String =
    this?.debugShortId() ?: "none"

fun String?.debugQuotedOrNone(): String =
    this?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "none"

@PublishedApi
internal const val PAIRING_LOG_TAG = "LinkPairing"

/**
 * Debug-only pairing trace.
 *
 * Never pass the word grid, the tapped words, the grid seed, a tile key, or any key material through here — the whole
 * point of the grid is that the answer stays off the device's logs. Sizes, counts, and [debugShortId]'d identifiers
 * only.
 */
internal inline fun pairingDebug(
    message: () -> String,
) {
    if (!LinkDiagnostics.verboseLogging) return
    Logger.d(messageString = message(), tag = PAIRING_LOG_TAG)
}
