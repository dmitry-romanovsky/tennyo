package app.tennyo.mobile.link.pairing

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.mobile.link.LinkTicket

object NoopKnownHostTargetStore : KnownHostTargetStore {
    override fun list(): List<KnownHostTarget> = emptyList()
    override fun get(companionId: String): KnownHostTarget? = null
    override fun save(target: KnownHostTarget) = Unit
    override fun remove(companionId: String) = Unit
    override fun updateFromDescriptor(
        descriptor: KnownHostDescriptor,
        transportFingerprint: String,
        resolvedHost: String?,
    ) = Unit
}

object NoopLocalNetworkDiscoveryGateway : LocalNetworkDiscoveryGateway {
    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> = emptyList()
}

object NoopRememberedPairStore : RememberedPairStore {
    override fun load(artifact: LinkTicket): RememberedPair? = null
    override fun save(artifact: LinkTicket, material: RememberedPairMaterial) = Unit
    override fun remove(artifact: LinkTicket) = Unit
    override fun removeByCompanionId(companionId: String) = Unit
}
