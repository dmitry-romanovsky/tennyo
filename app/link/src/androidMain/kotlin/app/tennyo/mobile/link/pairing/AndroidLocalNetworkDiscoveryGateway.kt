package app.tennyo.mobile.link.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.discoveryDebug
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidLocalNetworkDiscoveryGateway(
    context: Context,
) : LocalNetworkDiscoveryGateway {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> =
        suspendCancellableCoroutine { continuation ->
            val normalizedServiceType = browseServiceType(serviceType)
            val advertisements = linkedMapOf<String, LocalNetworkServiceAdvertisement>()
            var finished = false
            lateinit var listener: NsdManager.DiscoveryListener
            lateinit var timeoutRunnable: Runnable

            fun finish(reason: String) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeoutRunnable)
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                    .onFailure { throwable ->
                        discoveryDebug {
                            "android dns-sd stop failed serviceType=${normalizedServiceType.debugQuotedOrNone()} " +
                                "reason=${reason.debugQuotedOrNone()} error=${throwable.message.debugQuotedOrNone()}"
                        }
                    }
                if (continuation.isActive) {
                    discoveryDebug {
                        "android dns-sd finished serviceType=${normalizedServiceType.debugQuotedOrNone()} " +
                            "reason=${reason.debugQuotedOrNone()} resolved=${advertisements.size}"
                    }
                    continuation.resume(advertisements.values.toList())
                }
            }

            timeoutRunnable = Runnable {
                discoveryDebug {
                    "android dns-sd timeout serviceType=${normalizedServiceType.debugQuotedOrNone()} " +
                        "resolved=${advertisements.size}"
                }
                finish("timeout")
            }

            // NsdManager resolves one service at a time and rejects a second request with FAILURE_ALREADY_ACTIVE, so
            // found services queue and resolve in turn.
            val pendingResolves = ArrayDeque<NsdServiceInfo>()
            val resolveAttempts = mutableMapOf<String, Int>()
            var resolving = false
            lateinit var resolveNext: () -> Unit

            fun resolved() {
                resolving = false
                resolveNext()
            }

            fun resolve(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            discoveryDebug {
                                "android dns-sd resolve failed service=${serviceInfo.serviceName.debugShortId()} " +
                                    "errorCode=$errorCode"
                            }
                            val attempts = resolveAttempts.merge(serviceInfo.serviceName, 1, Int::plus) ?: 1
                            mainHandler.post {
                                if (attempts < MAX_RESOLVE_ATTEMPTS) pendingResolves.addLast(serviceInfo)
                                resolved()
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            mainHandler.post { resolved() }
                            val host = resolvedHost(serviceInfo) ?: return
                            if (serviceInfo.port <= 0) return
                            advertisements[serviceInfo.serviceName] = LocalNetworkServiceAdvertisement(
                                serviceType = normalizeServiceType(serviceType),
                                serviceInstanceId = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port,
                            )
                            discoveryDebug {
                                "android dns-sd resolved service=${serviceInfo.serviceName.debugShortId()} " +
                                    "host=${host.debugQuotedOrNone()} port=${serviceInfo.port}"
                            }
                        }
                    },
                )
            }

            resolveNext = {
                val next = if (resolving || finished) null else pendingResolves.removeFirstOrNull()
                if (next != null) {
                    resolving = true
                    resolve(next)
                }
            }

            fun enqueue(serviceInfo: NsdServiceInfo) {
                if (pendingResolves.none { it.serviceName == serviceInfo.serviceName }) {
                    pendingResolves.addLast(serviceInfo)
                }
                resolveNext()
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryDebug {
                        "android dns-sd start failed serviceType=${serviceType.debugQuotedOrNone()} " +
                            "errorCode=$errorCode"
                    }
                    finish("start_failed:$errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryDebug {
                        "android dns-sd stop callback failed serviceType=${serviceType.debugQuotedOrNone()} " +
                            "errorCode=$errorCode"
                    }
                    finish("stop_failed:$errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    discoveryDebug {
                        "android dns-sd started serviceType=${serviceType.debugQuotedOrNone()}"
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    discoveryDebug {
                        "android dns-sd stopped serviceType=${serviceType.debugQuotedOrNone()}"
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    discoveryDebug {
                        "android dns-sd found rawService=${serviceInfo.serviceType.debugQuotedOrNone()} " +
                            "name=${serviceInfo.serviceName.debugShortId()}"
                    }
                    if (normalizeServiceType(serviceInfo.serviceType) != normalizeServiceType(normalizedServiceType)) {
                        return
                    }
                    mainHandler.post { enqueue(serviceInfo) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    advertisements.remove(serviceInfo.serviceName)
                    discoveryDebug {
                        "android dns-sd lost service=${serviceInfo.serviceName.debugShortId()}"
                    }
                }
            }

            continuation.invokeOnCancellation { finish("cancelled") }
            mainHandler.post {
                runCatching {
                    discoveryDebug {
                        "android dns-sd browse start serviceType=${normalizedServiceType.debugQuotedOrNone()} " +
                            "timeoutMs=$timeoutMillis"
                    }
                    nsdManager.discoverServices(normalizedServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    mainHandler.postDelayed(timeoutRunnable, timeoutMillis)
                }.onFailure { throwable ->
                    discoveryDebug {
                        "android dns-sd browse threw serviceType=${normalizedServiceType.debugQuotedOrNone()} " +
                            "error=${throwable.message.debugQuotedOrNone()}"
                    }
                    finish("browse_exception")
                }
            }
        }

    private fun resolvedHost(serviceInfo: NsdServiceInfo): String? =
        serviceInfo.host?.hostAddress?.takeIf { it.isNotBlank() }

    private fun normalizeServiceType(value: String): String =
        value.trim().trimEnd('.')

    private fun browseServiceType(value: String): String =
        normalizeServiceType(value) + "."

    private companion object {
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}
