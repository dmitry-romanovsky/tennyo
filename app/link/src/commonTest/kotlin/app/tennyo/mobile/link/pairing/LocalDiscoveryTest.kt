package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.Discovery
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.link.transport.installLinkErrorHandling
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class LocalDiscoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun discovery(
        httpClient: HttpClient,
        rememberedPairStore: RememberedPairStore = NoopRememberedPairStore,
        targetStore: KnownHostTargetStore = NoopKnownHostTargetStore,
        localNetworkDiscoveryGateway: LocalNetworkDiscoveryGateway = NoopLocalNetworkDiscoveryGateway,
    ): LocalDiscovery {
        val identity = DeviceIdentity()
        return LocalDiscovery(
            http = LinkHttp(httpClient, identity),
            deviceIdentity = identity,
            rememberedPairs = RememberedPairs(rememberedPairStore),
            knownHosts = KnownHosts(targetStore, rememberedPairStore),
            gateway = localNetworkDiscoveryGateway,
        )
    }

    @Test
    fun discoverCandidatesUsesDnsSdEndpointInsteadOfRememberedHostFallback(): Unit = runBlocking {
        var requestedHost = ""
        var requestedPath = ""
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedHost = request.url.host
                    requestedPath = request.url.encodedPath
                    respond(
                        content = json.encodeToString(
                            DiscoverResponse(
                                status = DiscoveryStatus.AVAILABLE,
                                descriptor = KnownHostDescriptor(
                                    companionId = "companion-1",
                                    companionLabel = "Studio",
                                    projectName = "Tennyo",
                                    hostVersion = "2026.3",
                                    lastUsedAtEpochMillis = 42L,
                                ),
                            )
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            installLinkErrorHandling(json)
        }
        val targetStore = InMemoryKnownHostTargetStore(
            KnownHostTarget(
                companionId = "companion-1",
                companionLabel = "Studio",
                lastProjectName = "Tennyo",
                hostVersion = "2026.2",
                lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                discoveryTransportFingerprint = "fingerprint",
                lastResolvedHost = null,
            )
        )
        val discovery = discovery(
            httpClient = client,
            rememberedPairStore = InMemoryRememberedPairStore(
                "companion-1" to RememberedPair(
                    pairId = "pair-1",
                    pairSecret = "secret",
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
            ),
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(COMPANION_ONE_SERVICE),
        )

        val response = discovery.discoverCandidates(discovery.knownHostTargets(), listOf(COMPANION_ONE_SERVICE), dialStored = false)

        assertEquals("192.168.1.41", requestedHost)
        assertEquals("/api/tennyo/link/discover", requestedPath)
        assertEquals(DiscoveryStatus.AVAILABLE, response["companion-1"]?.response?.status)
        assertEquals(
            "https://192.168.1.41:63345",
            targetStore.get("companion-1")?.lastResolvedHost,
        )
        assertNotNull(targetStore.get("companion-1"))
    }

    @Test
    fun discoverCandidatesIgnoreDnsSdServicesForDifferentCompanionIds(): Unit = runBlocking {
        var requestCount = 0
        val targetStore = InMemoryKnownHostTargetStore(
            KnownHostTarget(
                companionId = "companion-1",
                companionLabel = "Studio",
                lastProjectName = "Tennyo",
                hostVersion = "2026.2",
                lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                discoveryTransportFingerprint = "fingerprint",
                lastResolvedHost = "https://192.168.1.41:63345/api/tennyo",
            )
        )
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        requestCount += 1
                        error("network should not be used for mismatched DNS-SD services")
                    }
                }
                install(ContentNegotiation) { json(json) }
                installLinkErrorHandling(json)
            },
            rememberedPairStore = InMemoryRememberedPairStore(
                "companion-1" to RememberedPair(
                    pairId = "pair-1",
                    pairSecret = "secret",
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
            ),
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(OTHER_COMPANION_SERVICE),
        )

        val response = discovery.discoverCandidates(discovery.knownHostTargets(), listOf(OTHER_COMPANION_SERVICE), dialStored = false)

        assertNull(response["companion-1"])
        assertEquals(0, requestCount)
        assertEquals(
            "https://192.168.1.41:63345/api/tennyo",
            targetStore.get("companion-1")?.lastResolvedHost,
        )
    }

    /**
     * DNS-SD renames an instance whose name is already taken ("id (2)"), and a host can be advertised on an address
     * the phone cannot reach. Neither may hide a known host the phone can reach on another of its services.
     */
    @Test
    fun aRenamedServiceIsProbedAndOneFailingAddressDoesNotHideTheHost(): Unit = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedHosts += request.url.host
                    if (request.url.host == UNREACHABLE_RENAMED_SERVICE.host) error("no route to host")
                    respond(
                        content = json.encodeToString(DiscoverResponse(status = DiscoveryStatus.AVAILABLE)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(json) }
            installLinkErrorHandling(json)
        }
        val discovery = discovery(
            httpClient = client,
            rememberedPairStore = InMemoryRememberedPairStore(
                "companion-1" to RememberedPair(
                    pairId = "pair-1",
                    pairSecret = "secret",
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
            ),
            targetStore = InMemoryKnownHostTargetStore(
                KnownHostTarget(
                    companionId = "companion-1",
                    companionLabel = "Studio",
                    hostVersion = "2026.2",
                    lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    discoveryTransportFingerprint = "fingerprint",
                ),
            ),
        )
        val services = listOf(UNREACHABLE_RENAMED_SERVICE, RENAMED_SERVICE)

        val response = discovery.discoverCandidates(discovery.knownHostTargets(), services, dialStored = false)

        assertEquals(DiscoveryStatus.AVAILABLE, response["companion-1"]?.response?.status)
        assertEquals(listOf(UNREACHABLE_RENAMED_SERVICE.host, RENAMED_SERVICE.host), requestedHosts)
    }

    @Test
    fun hostsUnusedPastTheRememberedPairLifetimeAreForgotten() {
        val staleTarget = KnownHostTarget(
            companionId = "stale-companion",
            companionLabel = "Old Mac",
            lastProjectName = "Tennyo",
            hostVersion = "2026.1",
            lastUsedAtEpochMillis = 0L,
            discoveryTransportFingerprint = "fingerprint",
        )
        val targetStore = InMemoryKnownHostTargetStore(staleTarget)
        val rememberedPairStore = InMemoryRememberedPairStore(
            staleTarget.companionId to RememberedPair(
                pairId = "pair-1",
                pairSecret = "secret",
                expiresAtEpochMillis = Long.MAX_VALUE,
            ),
        )
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { error("network should not be used") } }
                install(ContentNegotiation) { json(json) }
            },
            rememberedPairStore = rememberedPairStore,
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(),
        )

        val visibleTargets = discovery.knownHostTargets()

        assertTrue(visibleTargets.isEmpty())
        assertNull(targetStore.get(staleTarget.companionId))
        assertEquals(listOf(staleTarget.companionId), rememberedPairStore.removedCompanionIds)
    }

    @Test
    fun hostIsKeptWhenPairLookupMisses() {
        val staleTarget = KnownHostTarget(
            companionId = "missing-pair-companion",
            companionLabel = "Old Mac",
            lastProjectName = "Tennyo",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            discoveryTransportFingerprint = "fingerprint",
        )
        val targetStore = InMemoryKnownHostTargetStore(staleTarget)
        val rememberedPairStore = InMemoryRememberedPairStore()
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { error("network should not be used") } }
                install(ContentNegotiation) { json(json) }
            },
            rememberedPairStore = rememberedPairStore,
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(),
        )

        val visibleTargets = discovery.knownHostTargets()

        assertEquals(listOf(staleTarget), visibleTargets)
        assertEquals(staleTarget, targetStore.get(staleTarget.companionId))
        assertTrue(rememberedPairStore.removedCompanionIds.isEmpty())
    }

    @Test
    fun descriptorUpdateKeepsExistingDeviceLabelInsteadOfReplacingItWithProjectName(): Unit = runBlocking {
        val targetStore = InMemoryKnownHostTargetStore(
            KnownHostTarget(
                companionId = "companion-1",
                companionLabel = "Old Mac",
                lastProjectName = "Tennyo",
                hostVersion = "2026.2",
                lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                discoveryTransportFingerprint = "fingerprint",
            )
        )
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = json.encodeToString(
                                DiscoverResponse(
                                    status = DiscoveryStatus.AVAILABLE,
                                    descriptor = KnownHostDescriptor(
                                        companionId = "companion-1",
                                        companionLabel = "",
                                        projectName = "Another Project",
                                        hostVersion = "2026.3",
                                        lastUsedAtEpochMillis = 99L,
                                    ),
                                )
                            ),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                install(ContentNegotiation) { json(json) }
                installLinkErrorHandling(json)
            },
            rememberedPairStore = InMemoryRememberedPairStore(
                "companion-1" to RememberedPair(
                    pairId = "pair-1",
                    pairSecret = "secret",
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
            ),
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(COMPANION_ONE_SERVICE),
        )

        discovery.discoverCandidates(discovery.knownHostTargets(), listOf(COMPANION_ONE_SERVICE), dialStored = false)

        assertEquals("Old Mac", targetStore.get("companion-1")?.companionLabel)
        assertEquals("Another Project", targetStore.get("companion-1")?.lastProjectName)
    }

    @Test
    fun hostsStayStableAcrossRefreshes(): Unit = runBlocking {
        val target = KnownHostTarget(
            companionId = "companion-1",
            companionLabel = "Studio",
            lastProjectName = "Tennyo",
            hostVersion = "2026.2",
            lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            discoveryTransportFingerprint = "fingerprint",
        )
        val targetStore = InMemoryKnownHostTargetStore(target)
        val rememberedPairStore = InMemoryRememberedPairStore(
            "companion-1" to RememberedPair(
                pairId = "pair-1",
                pairSecret = "secret",
                expiresAtEpochMillis = Long.MAX_VALUE,
            ),
        )
        val descriptorLastUsedAt = Clock.System.now().toEpochMilliseconds()
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = json.encodeToString(
                                DiscoverResponse(
                                    status = DiscoveryStatus.AVAILABLE,
                                    descriptor = KnownHostDescriptor(
                                        companionId = "companion-1",
                                        companionLabel = "Studio",
                                        projectName = "Another Project",
                                        hostVersion = "2026.3",
                                        lastUsedAtEpochMillis = descriptorLastUsedAt,
                                    ),
                                )
                            ),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                install(ContentNegotiation) { json(json) }
                installLinkErrorHandling(json)
            },
            rememberedPairStore = rememberedPairStore,
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(COMPANION_ONE_SERVICE),
        )
        val firstResponse = discovery.discoverCandidates(discovery.knownHostTargets(), listOf(COMPANION_ONE_SERVICE), dialStored = false)
        discovery.discoverCandidates(discovery.knownHostTargets(), listOf(COMPANION_ONE_SERVICE), dialStored = false)
        val visibleTargets = discovery.knownHostTargets()

        assertEquals(DiscoveryStatus.AVAILABLE, firstResponse[target.companionId]?.response?.status)
        assertEquals(listOf(target.companionId), visibleTargets.map { it.companionId })
        assertEquals("Another Project", targetStore.get(target.companionId)?.lastProjectName)
        assertTrue(rememberedPairStore.hasCanonicalPair(target.companionId))
    }

    @Test
    fun forgetRemovesPairMaterial(): Unit = runBlocking {
        val target = KnownHostTarget(
            companionId = "companion-1",
            companionLabel = "Studio",
            lastProjectName = "Tennyo",
            hostVersion = "2026.2",
            lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            discoveryTransportFingerprint = "fingerprint",
            lastResolvedHost = "https://192.168.1.41:63345/api/tennyo",
        )
        val targetStore = InMemoryKnownHostTargetStore(target)
        val rememberedPairStore = InMemoryRememberedPairStore(
            "companion-1" to RememberedPair(
                pairId = "pair-1",
                pairSecret = "secret",
                expiresAtEpochMillis = Long.MAX_VALUE,
            ),
        )
        val discovery = discovery(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { error("network should not be used") } }
                install(ContentNegotiation) { json(json) }
            },
            rememberedPairStore = rememberedPairStore,
            targetStore = targetStore,
            localNetworkDiscoveryGateway = FakeLocalNetworkDiscoveryGateway(),
        )

        discovery.forgetKnownHostTarget(target.companionId)

        assertNull(targetStore.get(target.companionId))
        assertTrue(!rememberedPairStore.hasCanonicalPair(target.companionId))
    }
}

private class FakeLocalNetworkDiscoveryGateway(
    private vararg val advertisements: LocalNetworkServiceAdvertisement,
) : LocalNetworkDiscoveryGateway {
    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> = advertisements.toList()
}

/** The one host these tests put on the network, as the pairing screen's browse would report it. */
private val COMPANION_ONE_SERVICE = LocalNetworkServiceAdvertisement(
    serviceType = Discovery.SERVICE_TYPE,
    serviceInstanceId = "companion-1",
    host = "192.168.1.41",
    port = 63345,
)

/** The same host as [COMPANION_ONE_SERVICE] after DNS-SD resolved a name conflict, on an address that fails. */
private val UNREACHABLE_RENAMED_SERVICE = LocalNetworkServiceAdvertisement(
    serviceType = Discovery.SERVICE_TYPE,
    serviceInstanceId = "companion-1 (2)",
    host = "172.16.0.2",
    port = 63345,
)

private val RENAMED_SERVICE =
    UNREACHABLE_RENAMED_SERVICE.copy(serviceInstanceId = "companion-1 (3)", host = "192.168.1.41")

private val OTHER_COMPANION_SERVICE = LocalNetworkServiceAdvertisement(
    serviceType = Discovery.SERVICE_TYPE,
    serviceInstanceId = "different-companion",
    host = "192.168.1.99",
    port = 63345,
)

private class InMemoryRememberedPairStore(
    vararg pairs: Pair<String, RememberedPair>,
) : RememberedPairStore {
    private val pairsByStorageKey = linkedMapOf<String, RememberedPair>()
    val removedCompanionIds = mutableListOf<String>()
    var canonicalWriteCount = 0
        private set

    init {
        pairs.forEach { (companionId, pair) ->
            pairsByStorageKey[companionRememberedPairStorageKey(companionId)] = pair
        }
    }

    override fun load(artifact: LinkTicket): RememberedPair? =
        artifact.canonicalRememberedPairStorageKey()
            ?.let { storageKey -> pairsByStorageKey[storageKey] }

    override fun removeByCompanionId(companionId: String) {
        pairsByStorageKey.remove(companionRememberedPairStorageKey(companionId))
        removedCompanionIds += companionId
    }

    override fun save(
        artifact: LinkTicket,
        material: RememberedPairMaterial,
    ) {
        val storageCompanionId = material.companionId ?: artifact.companionId ?: return
        val savedPair = RememberedPair(
            pairId = material.pairId,
            pairSecret = material.pairSecret,
            expiresAtEpochMillis = material.expiresAtEpochMillis,
        )
        pairsByStorageKey[companionRememberedPairStorageKey(storageCompanionId)] = savedPair
        canonicalWriteCount += 1
    }

    override fun remove(artifact: LinkTicket) {
        artifact.canonicalRememberedPairStorageKey()?.let(pairsByStorageKey::remove)
    }

    fun hasCanonicalPair(companionId: String): Boolean =
        pairsByStorageKey.containsKey(companionRememberedPairStorageKey(companionId))

}

private class InMemoryKnownHostTargetStore(
    target: KnownHostTarget,
) : KnownHostTargetStore {
    private val targets = linkedMapOf(target.companionId to target)

    override fun list(): List<KnownHostTarget> = targets.values.toList()

    override fun get(companionId: String): KnownHostTarget? = targets[companionId]

    override fun save(target: KnownHostTarget) {
        targets[target.companionId] = target
    }

    override fun remove(companionId: String) {
        targets.remove(companionId)
    }

    override fun updateFromDescriptor(
        descriptor: KnownHostDescriptor,
        transportFingerprint: String,
        resolvedHost: String?,
    ) {
        val existing = targets[descriptor.companionId]
        save(
            KnownHostTarget(
                companionId = descriptor.companionId,
                companionLabel = descriptor.companionLabel.ifBlank { existing?.companionLabel.orEmpty() },
                lastProjectName = descriptor.projectName.takeIf(String::isNotBlank) ?: existing?.lastProjectName,
                hostVersion = descriptor.hostVersion,
                lastUsedAtEpochMillis = descriptor.lastUsedAtEpochMillis,
                discoveryTransportFingerprint = transportFingerprint,
                lastResolvedHost = resolvedHost ?: existing?.lastResolvedHost,
                lastKnownNetworkLabel = descriptor.lastKnownNetworkLabel ?: existing?.lastKnownNetworkLabel,
            )
        )
    }
}
