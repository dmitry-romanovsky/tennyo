package app.tennyo.mobile.root

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.mobile.link.LinkSessions
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.FirstPairing
import app.tennyo.mobile.link.pairing.KnownHostTarget
import app.tennyo.mobile.link.pairing.KnownHostTargetStore
import app.tennyo.mobile.link.pairing.KnownHosts
import app.tennyo.mobile.link.pairing.LocalDiscovery
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.LocalNetworkServiceAdvertisement
import app.tennyo.mobile.link.pairing.RememberedPair
import app.tennyo.mobile.link.pairing.RememberedPairMaterial
import app.tennyo.mobile.link.pairing.RememberedPairResume
import app.tennyo.mobile.link.pairing.RememberedPairStore
import app.tennyo.mobile.link.pairing.RememberedPairs
import app.tennyo.mobile.link.pairing.SessionEndpoints
import app.tennyo.mobile.link.session.LinkServices
import app.tennyo.mobile.link.session.SecureCredentialStorage
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.transport.LinkChannels
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.link.transport.installLinkErrorHandling
import app.tennyo.mobile.link.transport.linkJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** An HTTP client over [engine]; by default every request fails, for tests that must not reach a host. */
internal fun testHttpClient(
    engine: HttpClientEngine = MockEngine { error("network is not used by this test") },
): HttpClient {
    val json = linkJson()
    return HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        installLinkErrorHandling(json)
    }
}

/** A transport over [engine]; by default every request fails, for tests that must not reach a host. */
internal fun testLinkHttp(
    engine: HttpClientEngine = MockEngine { error("network is not used by this test") },
    deviceIdentity: DeviceIdentity = DeviceIdentity(),
): LinkHttp = LinkHttp(testHttpClient(engine), deviceIdentity)

internal fun testLinkSessions(
    secureStorage: SecureCredentialStorage,
    notification: SessionNotification,
    http: LinkHttp = testLinkHttp(),
): LinkSessions {
    val identity = DeviceIdentity()
    val rememberedPairs = RememberedPairs(NoopRememberedPairStore)
    val knownHosts = KnownHosts(NoopKnownHostTargetStore, NoopRememberedPairStore)
    val services = LinkServices(
        http = http,
        endpoints = SessionEndpoints(http, identity, rememberedPairs, knownHosts, clientVersion = "test"),
        firstPairing = FirstPairing(http, identity, NoopRememberedPairStore, knownHosts),
        rememberedPairResume = RememberedPairResume(http, identity, rememberedPairs, knownHosts),
        channels = LinkChannels(httpClient = testHttpClient(), deviceIdentity = identity),
        credentials = SessionCredentialStore(secureStorage),
        notification = notification,
    )
    return LinkSessions(services)
}

internal fun testKnownHostDiscovery(
    targets: KnownHostTargetStore = NoopKnownHostTargetStore,
    http: LinkHttp = testLinkHttp(),
): LocalDiscovery =
    LocalDiscovery(
        http = http,
        deviceIdentity = DeviceIdentity(),
        rememberedPairs = RememberedPairs(NoopRememberedPairStore),
        knownHosts = KnownHosts(targets, NoopRememberedPairStore),
        gateway = NoopLocalNetworkDiscoveryGateway,
    )

internal object NoopKnownHostTargetStore : KnownHostTargetStore {
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

internal object NoopLocalNetworkDiscoveryGateway : LocalNetworkDiscoveryGateway {
    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> = emptyList()
}

internal object NoopRememberedPairStore : RememberedPairStore {
    override fun load(artifact: LinkTicket): RememberedPair? = null
    override fun save(artifact: LinkTicket, material: RememberedPairMaterial) = Unit
    override fun remove(artifact: LinkTicket) = Unit
    override fun removeByCompanionId(companionId: String) = Unit
}
