package app.tennyo.host.intellij

import app.tennyo.contract.Discovery
import app.tennyo.contract.ProtocolVersions
import app.tennyo.host.engine.discovery.DiscoveryAdvertiser
import app.tennyo.host.engine.platform.debugQuotedOrNone
import app.tennyo.host.engine.platform.debugShortId
import com.intellij.openapi.diagnostic.Logger
import java.net.InetAddress
import java.net.URI
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

internal object DnsSdAdvertisement : DiscoveryAdvertiser {
    private val logger = Logger.getInstance(DnsSdAdvertisement::class.java)
    private val lock = Any()
    private var jmdns: JmDNS? = null
    private var boundAddress: String? = null
    private val servicesByCompanionId = linkedMapOf<String, ServiceInfo>()

    override fun advertise(companionId: String, endpoint: String, pairingWindowOpen: Boolean) {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return
        val port = uri.port.takeIf { it > 0 } ?: return
        synchronized(lock) {
            val server = runCatching { serverFor(host) }
                .onFailure { throwable ->
                    logger.warn("Tennyo Companion DNS-SD server setup failed for $host:$port", throwable)
                }
                .getOrNull()
                ?: return
            servicesByCompanionId.remove(companionId)?.let { existing ->
                runCatching { server.unregisterService(existing) }
                    .onFailure { throwable ->
                        logger.warn("Tennyo Companion DNS-SD unregister failed for $companionId", throwable)
                    }
            }
            // The TXT record is a probe-order hint only: `v` so an old phone can skip us, `p` while a pairing
            // window is open. The offer itself is the answer, and its short id is what the phone verifies.
            val info = ServiceInfo.create(
                SERVICE_TYPE_FQDN,
                companionId,
                port,
                0,
                0,
                mapOf(
                    Discovery.TXT_SECURITY_VERSION to ProtocolVersions.SECURITY_VERSION.toString(),
                    Discovery.TXT_PAIRING_WINDOW to if (pairingWindowOpen) "1" else "0",
                ),
            )
            runCatching { server.registerService(info) }
                .onFailure { throwable ->
                    logger.warn("Tennyo Companion DNS-SD advertise failed for $companionId", throwable)
                }
                .getOrNull()
                ?: return
            servicesByCompanionId[companionId] = info
            logger.info("Tennyo Companion DNS-SD advertised service $companionId on $host:$port")
            tennyoDiscoveryDebug(logger) {
                "dns-sd advertised service=${companionId.debugShortId()} host=${host.debugQuotedOrNone()} port=$port registrations=${servicesByCompanionId.size}"
            }
        }
    }

    override fun unadvertise(companionId: String) {
        synchronized(lock) {
            val server = jmdns ?: return
            servicesByCompanionId.remove(companionId)?.let { info ->
                runCatching { server.unregisterService(info) }
                    .onFailure { throwable ->
                        logger.warn("Tennyo Companion DNS-SD unadvertise failed for $companionId", throwable)
                    }
                logger.info("Tennyo Companion DNS-SD removed service $companionId")
                tennyoDiscoveryDebug(logger) {
                    "dns-sd removed service=${companionId.debugShortId()} registrations=${servicesByCompanionId.size}"
                }
            }
            if (servicesByCompanionId.isEmpty()) {
                runCatching { server.close() }
                    .onFailure { throwable ->
                        logger.warn("Tennyo Companion DNS-SD server close failed", throwable)
                    }
                jmdns = null
                boundAddress = null
                logger.info("Tennyo Companion DNS-SD server closed")
                tennyoDiscoveryDebug(logger) { "dns-sd browser server closed" }
            }
        }
    }

    private fun serverFor(host: String): JmDNS {
        val address = InetAddress.getByName(host)
        val normalizedAddress = address.hostAddress
        val existing = jmdns
        if (existing != null && boundAddress == normalizedAddress) {
            return existing
        }
        existing?.close()
        return JmDNS.create(address, "tennyo-$normalizedAddress").also { created ->
            jmdns = created
            boundAddress = normalizedAddress
        }
    }

    private const val SERVICE_TYPE_FQDN: String = "${Discovery.SERVICE_TYPE}.local."
}
