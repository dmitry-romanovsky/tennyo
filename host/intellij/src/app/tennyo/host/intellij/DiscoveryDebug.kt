package app.tennyo.host.intellij

import com.intellij.openapi.diagnostic.Logger

private const val DISCOVERY_DEBUG_PREFIX = "[discovery] "

internal inline fun tennyoDiscoveryDebug(
    logger: Logger,
    message: () -> String,
) {
    HostLoggingPolicy.debug(logger) {
        DISCOVERY_DEBUG_PREFIX + message()
    }
}
