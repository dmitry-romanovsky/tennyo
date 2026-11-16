package app.tennyo.mobile.feature.connect

import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.OfferResponse
import app.tennyo.contract.PairingOffer
import app.tennyo.contract.PairingOfferStatus
import app.tennyo.contract.ProjectChoice
import app.tennyo.contract.ProtocolVersions
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.DiscoveryConnectResult
import app.tennyo.mobile.link.pairing.KnownHostAnswer
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.KnownHostReach
import app.tennyo.mobile.link.pairing.KnownHostTarget
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.LocalNetworkServiceAdvertisement
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.pairing.PairingTarget
import app.tennyo.mobile.link.pairing.RemoteAddressError
import app.tennyo.mobile.link.transport.LinkHttp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class RealConnectComponentTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun multiProjectHostAsksForProject(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = {
                DiscoverResponse(
                    status = DiscoveryStatus.AVAILABLE,
                    challenge = DiscoveryChallenge(
                        challengeId = "challenge-1",
                        serverChallenge = "server-challenge",
                        connectToken = "connect-token",
                        companionEphemeralPublicKey = "companion-ephemeral",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    projectChoices = listOf(
                        ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                        ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                    ),
                )
            },
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))

        val selection = component.state.value.projectSelection
        assertEquals(target.companionId, selection?.targetId)
        assertEquals(listOf("project-a", "project-b"), selection?.projects?.map { it.projectId })
    }

    @Test
    fun projectSelectionConfirmedStartsSelectedProjectAndClosesSheet(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = {
                DiscoverResponse(
                    status = DiscoveryStatus.AVAILABLE,
                    challenge = DiscoveryChallenge(
                        challengeId = "challenge-1",
                        serverChallenge = "server-challenge",
                        connectToken = "connect-token",
                        companionEphemeralPublicKey = "companion-ephemeral",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    projectChoices = listOf(
                        ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                        ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                    ),
                )
            },
            startDiscoverySessionResult = DiscoveryConnectResult.Success(
                LinkTicket(
                    endpoint = "https://127.0.0.1/api/tennyo",
                    sessionId = "session-1",
                    token = "token-1",
                    fingerprint = "fingerprint-1",
                    projectName = "Project B",
                    projectPath = "/tmp/project-b",
                    companionId = target.companionId,
                ),
            ),
        )
        val acceptedTickets = mutableListOf<LinkTicket>()
        val component = connectComponent(
            discovery = discovery,
            onSessionTicket = acceptedTickets::add,
        )

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))
        component.onIntent(
            ConnectIntent.ProjectSelectionConfirmed(
                targetId = target.companionId,
                projectId = "project-b",
            )
        )

        assertEquals(listOf<String?>("project-b"), discovery.startedProjectIds)
        assertNull(component.state.value.projectSelection)
        assertEquals(1, acceptedTickets.size)
    }

    @Test
    fun projectSelectionConfirmedShowsLoaderThenClearsItOnSuccess(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = {
                DiscoverResponse(
                    status = DiscoveryStatus.AVAILABLE,
                    challenge = DiscoveryChallenge(
                        challengeId = "challenge-1",
                        serverChallenge = "server-challenge",
                        connectToken = "connect-token",
                        companionEphemeralPublicKey = "companion-ephemeral",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    projectChoices = listOf(
                        ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                        ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                    ),
                )
            },
            startDiscoverySessionResult = DiscoveryConnectResult.Success(
                LinkTicket(
                    endpoint = "https://127.0.0.1/api/tennyo",
                    sessionId = "session-1",
                    token = "token-1",
                    fingerprint = "fingerprint-1",
                    projectName = "Project A",
                    projectPath = "/tmp/project-a",
                    companionId = target.companionId,
                ),
            ),
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNotNull(component.state.value.projectSelection)

        component.onIntent(
            ConnectIntent.ProjectSelectionConfirmed(
                targetId = target.companionId,
                projectId = "project-a",
            )
        )

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNull(component.state.value.projectSelection)
        assertNull(component.state.value.discoveryError)
    }

    @Test
    fun projectSelectionConfirmedShowsErrorWithBlockedMessageOnFailure(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = {
                DiscoverResponse(
                    status = DiscoveryStatus.AVAILABLE,
                    challenge = DiscoveryChallenge(
                        challengeId = "challenge-1",
                        serverChallenge = "server-challenge",
                        connectToken = "connect-token",
                        companionEphemeralPublicKey = "companion-ephemeral",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    projectChoices = listOf(
                        ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                        ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                    ),
                )
            },
            startDiscoverySessionResult = DiscoveryConnectResult.Failed(
                status = DiscoveryStatus.UNAVAILABLE,
                blockedMessage = "Target project is not registered",
            ),
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))
        assertNotNull(component.state.value.projectSelection)

        component.onIntent(
            ConnectIntent.ProjectSelectionConfirmed(
                targetId = target.companionId,
                projectId = "project-a",
            )
        )

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNull(component.state.value.projectSelection)
        val error = component.state.value.discoveryError
        assertNotNull(error)
        assertEquals(ConnectFailure.SessionStartFailed, error.failure)
        assertEquals(UiText.Verbatim("Target project is not registered"), error.details)
    }

    @Test
    fun projectSelectionLoaderStateTransitionsCorrectly(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = {
                DiscoverResponse(
                    status = DiscoveryStatus.AVAILABLE,
                    challenge = DiscoveryChallenge(
                        challengeId = "challenge-1",
                        serverChallenge = "server-challenge",
                        connectToken = "connect-token",
                        companionEphemeralPublicKey = "companion-ephemeral",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    projectChoices = listOf(
                        ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                        ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                    ),
                )
            },
            startDiscoverySessionResult = DiscoveryConnectResult.Failed(
                status = DiscoveryStatus.BLOCKED,
                blockedMessage = null,
            ),
        )
        val component = connectComponent(discovery = discovery)

        assertFalse(component.state.value.isStartingDiscoverySession)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))
        assertNotNull(component.state.value.projectSelection)

        component.onIntent(
            ConnectIntent.ProjectSelectionConfirmed(
                targetId = target.companionId,
                projectId = "project-a",
            )
        )

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNotNull(component.state.value.discoveryError)
    }

    @Test
    fun projectSelectionDismissedCancelsInFlightSessionStart(): Unit = runBlocking {
        val target = knownTarget("multi-project-target")
        val discoverGate = CompletableDeferred<DiscoverResponse?>()
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(target),
            discoverOne = { discoverGate.await() },
            startDiscoverySessionResult = DiscoveryConnectResult.Failed(
                status = DiscoveryStatus.UNAVAILABLE,
                blockedMessage = null,
            ),
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(target.companionId))

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNull(component.state.value.projectSelection)

        discoverGate.complete(
            DiscoverResponse(
                status = DiscoveryStatus.AVAILABLE,
                challenge = DiscoveryChallenge(
                    challengeId = "challenge-1",
                    serverChallenge = "server-challenge",
                    connectToken = "connect-token",
                    companionEphemeralPublicKey = "companion-ephemeral",
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
                projectChoices = listOf(
                    ProjectChoice("project-a", "Project A", "/tmp/project-a"),
                    ProjectChoice("project-b", "Project B", "/tmp/project-b"),
                ),
            )
        )

        assertNotNull(component.state.value.projectSelection)

        component.onIntent(
            ConnectIntent.ProjectSelectionConfirmed(
                targetId = target.companionId,
                projectId = "project-a",
            )
        )

        component.onIntent(ConnectIntent.ProjectSelectionDismissed)

        assertFalse(component.state.value.isStartingDiscoverySession)
        assertNull(component.state.value.projectSelection)
    }

    @Test
    fun forgetRemovesHostAndItsPair(): Unit = runBlocking {
        val first = knownTarget("first-target")
        val second = knownTarget("second-target")
        val discoverGate = CompletableDeferred<DiscoverResponse?>()
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(first, second),
            discoverOne = { target ->
                if (target.companionId == first.companionId) {
                    discoverGate.await()
                } else {
                    null
                }
            },
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(first.companionId))
        component.onIntent(ConnectIntent.ForgetKnownHostClicked(first.companionId))
        discoverGate.complete(null)

        assertEquals(listOf(second.companionId), component.state.value.knownHostTargets.map { it.companionId })
        assertEquals(listOf(first.companionId), discovery.forgottenTargetIds)
        assertNull(component.state.value.selectedDiscoveryTargetId)
        assertNull(component.state.value.projectSelection)
    }

    @Test
    fun tappingAHostInPairingModeHandsOverTheTargetTheProbeFound(): Unit = runBlocking {
        val picked = mutableListOf<PairingTarget>()
        val component = connectComponent(
            gateway = FakeGateway(PAIRING_SERVICE),
            pairingProbe = PairingProbe(
                http = unreachableLinkHttp(),
                observeFingerprint = { PAIRING_FINGERPRINT },
                pinFingerprint = { _, _ -> },
                offerFor = { _, _ -> openOffer() },
            ),
            onPairingHostSelected = picked::add,
        )

        val row = component.state.value.pairingHosts.single()
        component.onIntent(ConnectIntent.PairingHostSelected(row.shortId))

        assertEquals(listOf(row.shortId), picked.map { it.hostShortId })
        assertEquals("Amber Harbour", picked.single().hostName)
        assertEquals(PAIRING_FINGERPRINT, picked.single().fingerprint)
    }

    @Test
    fun aPassBrowsesOnceAndProbesKnownHostsOverThatBrowse(): Unit = runBlocking {
        val gateway = FakeGateway(PAIRING_SERVICE)
        val browsedServices = mutableListOf<List<LocalNetworkServiceAdvertisement>>()
        val discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1")))
        discovery.discoverMany = { targets, services, _ ->
            browsedServices += services
            targets.associate {
                it.companionId to KnownHostAnswer(DiscoverResponse(status = DiscoveryStatus.AVAILABLE), KnownHostReach.NEARBY, "https://192.0.2.1:63343")
            }
        }

        val component = connectComponent(
            discovery = discovery,
            gateway = gateway,
            pairingProbe = PairingProbe(
                http = unreachableLinkHttp(),
                observeFingerprint = { PAIRING_FINGERPRINT },
                pinFingerprint = { _, _ -> },
                offerFor = { _, _ -> openOffer() },
            ),
        )

        assertEquals(1, gateway.browseCount)
        assertEquals(listOf(listOf(PAIRING_SERVICE)), browsedServices)
        assertEquals(
            listOf(KnownHostAvailability.AVAILABLE),
            component.state.value.knownHostTargets.map { it.availability },
        )
    }

    @Test
    fun knownHostsShowBeforeTheBrowseAnswers(): Unit = runBlocking {
        val stalledBrowse = object : LocalNetworkDiscoveryGateway {
            override suspend fun discoverServices(
                serviceType: String,
                timeoutMillis: Long,
            ): List<LocalNetworkServiceAdvertisement> = awaitCancellation()
        }

        val component = connectComponent(
            discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1"))),
            gateway = stalledBrowse,
        )

        assertEquals(listOf("companion-1"), component.state.value.knownHostTargets.map { it.companionId })
    }

    @Test
    fun `typing an address saves it and checks it and shows the answer`(): Unit = runBlocking {
        val discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1")))
        discovery.addressCheckResult = AddressCheck.NO_ANSWER
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.AddressesClicked("companion-1"))
        component.onIntent(ConnectIntent.AddressInputChanged("203.0.113.5:8443"))
        component.onIntent(ConnectIntent.AddressSubmitted)

        val sheet = assertNotNull(component.state.value.addresses)
        val endpoint = "https://203.0.113.5:8443/api/tennyo"
        assertEquals(listOf(endpoint), discovery.addedAddresses)
        assertEquals(listOf(endpoint), sheet.added)
        assertEquals(AddressCheckUiState(endpoint, AddressCheck.NO_ANSWER), sheet.check)
        assertFalse(sheet.isChecking)

        component.onIntent(ConnectIntent.AddressRemoveClicked(endpoint))
        assertEquals(listOf(endpoint), discovery.removedAddresses)
        assertEquals(emptyList(), component.state.value.addresses?.added)

        component.onIntent(ConnectIntent.AddressesDismissed)
        assertNull(component.state.value.addresses)
    }

    @Test
    fun `an address defaults to the port of the first ticket endpoint`(): Unit = runBlocking {
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(knownTarget("companion-1", ticketEndpoints = listOf("https://192.168.1.4:8443"))),
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.AddressesClicked("companion-1"))
        component.onIntent(ConnectIntent.AddressInputChanged("203.0.113.5"))
        component.onIntent(ConnectIntent.AddressSubmitted)

        assertEquals(listOf("https://203.0.113.5:8443/api/tennyo"), discovery.addedAddresses)
    }

    @Test
    fun `a connect goes to the endpoint that answered the discover probe`(): Unit = runBlocking {
        val response = DiscoverResponse(
            status = DiscoveryStatus.AVAILABLE,
            challenge = DiscoveryChallenge("c", "s", "t", "k", Long.MAX_VALUE),
        )
        val discovery = FakeRemoteConnectEntryClient(
            knownTargets = mutableListOf(knownTarget("companion-1")),
            discoveryResponses = mapOf("companion-1" to response),
        )
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.StartSessionAndConnectClicked("companion-1"))

        assertEquals(listOf(ANSWERING_ENDPOINT), discovery.startedEndpoints)
    }

    @Test
    fun `an invalid typed address is refused without saving it`(): Unit = runBlocking {
        val discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1")))
        val component = connectComponent(discovery = discovery)

        component.onIntent(ConnectIntent.AddressesClicked("companion-1"))
        component.onIntent(ConnectIntent.AddressInputChanged("x:0"))
        component.onIntent(ConnectIntent.AddressSubmitted)

        assertEquals(RemoteAddressError.BAD_PORT, component.state.value.addresses?.inputError)
        assertEquals(emptyList(), discovery.addedAddresses)
    }

    @Test
    fun `stored addresses are dialed at most once per interval while the screen stays resumed`(): Unit = runBlocking {
        val dials = mutableListOf<Boolean>()
        val discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1")))
        discovery.discoverMany = { _, _, dialStored ->
            dials += dialStored
            emptyMap()
        }
        connectComponent(discovery = discovery, now = { mainDispatcher.scheduler.currentTime })

        mainDispatcher.scheduler.advanceTimeBy(2_500L)
        assertEquals(listOf(true, false, false), dials)

        mainDispatcher.scheduler.advanceTimeBy(DIRECT_DIAL_INTERVAL_MILLIS)
        assertEquals(2, dials.count { it })
    }

    @Test
    fun `an away answer is held between dials and for two intervals after the last one`(): Unit = runBlocking {
        val discovery = FakeRemoteConnectEntryClient(knownTargets = mutableListOf(knownTarget("companion-1")))
        var dialAnswers = true
        discovery.discoverMany = { targets, _, dialStored ->
            targets.associate { target ->
                target.companionId to KnownHostAnswer(DiscoverResponse(status = DiscoveryStatus.AVAILABLE), KnownHostReach.AWAY, "https://192.0.2.1:63343")
                    .takeIf { dialStored && dialAnswers }
            }
        }
        val component = connectComponent(discovery = discovery, now = { mainDispatcher.scheduler.currentTime })
        fun availability() = component.state.value.knownHostTargets.single().availability

        assertEquals(KnownHostAvailability.AVAILABLE, availability())
        mainDispatcher.scheduler.advanceTimeBy(5_000L)
        assertEquals(KnownHostAvailability.AVAILABLE, availability())

        dialAnswers = false
        mainDispatcher.scheduler.advanceTimeBy(DIRECT_DIAL_INTERVAL_MILLIS)
        assertEquals(KnownHostAvailability.AVAILABLE, availability())
        mainDispatcher.scheduler.advanceTimeBy(DIRECT_DIAL_INTERVAL_MILLIS)
        assertEquals(KnownHostAvailability.UNAVAILABLE, availability())
    }

    private fun connectComponent(
        initialState: ConnectState = ConnectState(),
        discovery: KnownHostDiscovery = FakeRemoteConnectEntryClient(),
        gateway: LocalNetworkDiscoveryGateway = NoopLocalNetworkDiscoveryGateway,
        pairingProbe: PairingProbe = PairingProbe(http = unreachableLinkHttp()),
        onSessionTicket: (LinkTicket) -> Unit = {},
        onPairingHostSelected: (PairingTarget) -> Unit = {},
        now: () -> Long = { 0L },
    ): RealConnectComponent {
        // Resumed, because the pairing loop is what fills both lists and it runs only while the screen is on screen.
        val lifecycle = LifecycleRegistry()
        return RealConnectComponent(
            componentContext = DefaultComponentContext(lifecycle),
            discovery = discovery,
            gateway = gateway,
            pairingProbe = pairingProbe,
            onSessionTicket = onSessionTicket,
            onPairingHostSelected = onPairingHostSelected,
            backgroundDispatcher = mainDispatcher,
            now = now,
            onBackNav = {},
        ).also { component ->
            lifecycle.resume()
            component.onIntent(
                ConnectIntent.NotificationPermissionStatusLoaded(
                    NotificationPermissionStatus(isGranted = initialState.notificationPermission.isGranted),
                )
            )
        }
    }
}

private const val ANSWERING_ENDPOINT = "https://100.101.2.3:63343"

private fun knownTarget(companionId: String, ticketEndpoints: List<String> = emptyList()): KnownHostTarget =
    KnownHostTarget(
        companionId = companionId,
        companionLabel = "Studio",
        lastProjectName = "Tennyo",
        hostVersion = "2026.3",
        lastUsedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        discoveryTransportFingerprint = "fingerprint",
        lastKnownNetworkLabel = "Office LAN",
        ticketEndpoints = ticketEndpoints,
    )

private class FakeRemoteConnectEntryClient(
    knownTargets: List<KnownHostTarget> = emptyList(),
    discoveryResponses: Map<String, DiscoverResponse?> = emptyMap(),
    var discoverMany: suspend (
        List<KnownHostTarget>,
        List<LocalNetworkServiceAdvertisement>,
        Boolean,
    ) -> Map<String, KnownHostAnswer?> = { targets, _, _ ->
        targets.associate { target ->
            target.companionId to discoveryResponses[target.companionId]?.let { KnownHostAnswer(it, KnownHostReach.NEARBY, ANSWERING_ENDPOINT) }
        }
    },
    var discoverOne: suspend (KnownHostTarget) -> DiscoverResponse? = { target ->
        discoveryResponses[target.companionId]
    },
    private val startDiscoverySessionResult: DiscoveryConnectResult = DiscoveryConnectResult.Failed(
        status = DiscoveryStatus.UNAVAILABLE,
        blockedMessage = null,
    ),
) : KnownHostDiscovery {
    private val knownTargets = knownTargets.toMutableList()
    private val discoveryResponses = discoveryResponses.toMutableMap()
    val forgottenTargetIds = mutableListOf<String>()
    val startedProjectIds = mutableListOf<String?>()
    val startedEndpoints = mutableListOf<String>()

    override fun knownHostTargets(): List<KnownHostTarget> = knownTargets

    override suspend fun discoverCandidates(
        targets: List<KnownHostTarget>,
        services: List<LocalNetworkServiceAdvertisement>,
        dialStored: Boolean,
    ): Map<String, KnownHostAnswer?> = discoverMany(targets, services, dialStored)

    override suspend fun discoverCandidate(target: KnownHostTarget): KnownHostAnswer? =
        discoverOne(target)?.let { KnownHostAnswer(it, KnownHostReach.NEARBY, ANSWERING_ENDPOINT) }

    override suspend fun startDiscoverySession(
        target: KnownHostTarget,
        challenge: DiscoveryChallenge,
        endpoint: String,
        projectId: String?,
    ): DiscoveryConnectResult {
        startedEndpoints += endpoint
        startedProjectIds += projectId
        return startDiscoverySessionResult
    }

    val addedAddresses = mutableListOf<String>()
    val removedAddresses = mutableListOf<String>()
    var addressCheckResult = AddressCheck.REACHED

    override suspend fun addAddress(companionId: String, endpoint: String) {
        addedAddresses += endpoint
    }

    override suspend fun removeAddress(companionId: String, endpoint: String) {
        removedAddresses += endpoint
    }

    override suspend fun checkAddress(target: KnownHostTarget, endpoint: String): AddressCheck = addressCheckResult

    override suspend fun forgetKnownHostTarget(companionId: String) {
        forgottenTargetIds += companionId
        knownTargets.removeAll { it.companionId == companionId }
    }
}

/** The probe never reaches a host in these tests: the component's own behaviour is what is under test. */
private fun unreachableLinkHttp(): LinkHttp =
    LinkHttp(
        httpClient = HttpClient(MockEngine) {
            engine { addHandler { throw IllegalStateException("host unreachable") } }
        },
        deviceIdentity = DeviceIdentity(),
    )

private const val PAIRING_FINGERPRINT = "tYRnHkuT6CF0VoVeJ6oVUmR6KpHPRmGSk8Lp0iGdGio"

/** `HostShortId.of(PAIRING_FINGERPRINT)`, spelled out because a feature does not depend on `:shared:crypto`. */
private const val PAIRING_SHORT_ID = "9fa8c3a51ba1"

private val PAIRING_SERVICE = LocalNetworkServiceAdvertisement(
    serviceType = "_tennyo-link._tcp",
    serviceInstanceId = "companion-1",
    host = "192.0.2.10",
    port = 8443,
)

private fun openOffer() = OfferResponse(
    status = PairingOfferStatus.OPEN,
    offer = PairingOffer(
        pairingSessionId = "session-1",
        pairingToken = "pairing-token",
        hostName = "Amber Harbour",
        hostShortId = PAIRING_SHORT_ID,
        hostLabel = "VS Code 1.107",
        expiresAtEpochMillis = 1_700_000_000_000L,
        securityVersion = ProtocolVersions.SECURITY_VERSION,
    ),
)

/** One host on the network, every pass, counting what the loop asked of DNS-SD. */
private class FakeGateway(private vararg val services: LocalNetworkServiceAdvertisement) :
    LocalNetworkDiscoveryGateway {
    var browseCount = 0
        private set

    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> {
        browseCount += 1
        return services.toList()
    }
}

private object NoopLocalNetworkDiscoveryGateway : LocalNetworkDiscoveryGateway {
    override suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement> = emptyList()
}
