package app.tennyo.mobile.link.pairing

import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.discoveryDebug
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.coroutines.resume

/** Browses Bonjour for [LocalNetworkServiceAdvertisement]s and resolves each to a host and port. */
class IosLocalNetworkDiscoveryGateway : LocalNetworkDiscoveryGateway {
    // NSNetServiceBrowser delivers to the run loop it was started on; the main one is the only one always running,
    // so the whole session (start, callbacks, timeout and cancellation) lives on the main thread.
    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val session = BonjourBrowseSession(
                serviceType = serviceType.trim().trimEnd('.') + ".",
                onFinished = { continuation.resume(it) },
            )
            continuation.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { session.finish() } }
            session.start(timeoutMillis)
        }
    }
}

/** How long Bonjour may take to turn a found service into a host and port. */
private const val RESOLVE_TIMEOUT_SECONDS = 3.0

private class BonjourBrowseSession(
    private val serviceType: String,
    private val onFinished: (List<LocalNetworkServiceAdvertisement>) -> Unit,
) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
    private val browser = NSNetServiceBrowser()

    // Foundation keeps no reference to a service it hands the delegate; one nobody holds is freed mid-resolve.
    private val resolving = mutableListOf<NSNetService>()
    private val advertisements = linkedMapOf<String, LocalNetworkServiceAdvertisement>()
    private var finished = false

    fun start(timeoutMillis: Long) {
        discoveryDebug {
            "ios bonjour browse start serviceType=${serviceType.debugQuotedOrNone()} timeoutMs=$timeoutMillis"
        }
        browser.delegate = this
        browser.searchForServicesOfType(serviceType, inDomain = "local.")
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, timeoutMillis * NSEC_PER_MSEC.toLong()),
            dispatch_get_main_queue(),
        ) { finish() }
    }

    fun finish() {
        if (finished) return
        finished = true
        browser.stop()
        browser.delegate = null
        resolving.forEach { service ->
            service.stop()
            service.delegate = null
        }
        resolving.clear()
        discoveryDebug {
            "ios bonjour finished serviceType=${serviceType.debugQuotedOrNone()} resolved=${advertisements.size}"
        }
        onFinished(advertisements.values.toList())
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        discoveryDebug {
            "ios bonjour found service=${didFindService.name.debugShortId()} " +
                "type=${didFindService.type.debugQuotedOrNone()} moreComing=$moreComing"
        }
        resolving += didFindService
        didFindService.delegate = this
        didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
        discoveryDebug { "ios bonjour lost service=${didRemoveService.name.debugShortId()} moreComing=$moreComing" }
        advertisements.remove(didRemoveService.name)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        discoveryDebug { "ios bonjour search failed info=${didNotSearch.toString().debugQuotedOrNone()}" }
        finish()
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
        discoveryDebug { "ios bonjour stopped" }
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        resolving -= sender
        val host = sender.hostName?.trimEnd('.')?.takeIf(String::isNotBlank) ?: return
        val port = sender.port.toInt().takeIf { it > 0 } ?: return
        advertisements[sender.name] = LocalNetworkServiceAdvertisement(
            serviceType = serviceType,
            serviceInstanceId = sender.name,
            host = host,
            port = port,
        )
        discoveryDebug {
            "ios bonjour resolved service=${sender.name.debugShortId()} host=${host.debugQuotedOrNone()} port=$port"
        }
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        resolving -= sender
        discoveryDebug {
            "ios bonjour resolve failed service=${sender.name.debugShortId()} " +
                "info=${didNotResolve.toString().debugQuotedOrNone()}"
        }
    }
}
