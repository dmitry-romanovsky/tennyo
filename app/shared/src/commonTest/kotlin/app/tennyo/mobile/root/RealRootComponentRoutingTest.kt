package app.tennyo.mobile.root

import app.tennyo.mobile.feature.connect.HomeIntent
import app.tennyo.mobile.feature.git.GitPaneComponent
import app.tennyo.mobile.feature.local.CloneWorkspaceComponent
import app.tennyo.mobile.feature.local.CloneWorkspaceIntent
import app.tennyo.mobile.feature.local.CloneWorkspaceState
import app.tennyo.mobile.feature.local.CreateWorkspaceComponent
import app.tennyo.mobile.feature.local.CreateWorkspaceIntent
import app.tennyo.mobile.feature.local.CreateWorkspaceState
import app.tennyo.mobile.feature.local.GitSettingsComponent
import app.tennyo.mobile.feature.local.GitSettingsIntent
import app.tennyo.mobile.feature.local.GitSettingsState
import app.tennyo.mobile.feature.local.LocalHomeComponent
import app.tennyo.mobile.feature.local.LocalHomeIntent
import app.tennyo.mobile.feature.local.LocalHomeState
import app.tennyo.mobile.feature.local.LocalWorkspaceComponent
import app.tennyo.mobile.feature.local.LocalWorkspaceEffect
import app.tennyo.mobile.feature.local.LocalWorkspaceEntry
import app.tennyo.mobile.feature.local.LocalWorkspaceIntent
import app.tennyo.mobile.feature.local.LocalWorkspaceState
import app.tennyo.mobile.feature.settings.AppSettingsStore
import app.tennyo.mobile.feature.terminal.TerminalPreferences
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.session.StoredSession
import app.tennyo.mobile.link.session.toStoredArtifact
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.workspace.FakeSecureStorage
import app.tennyo.mobile.workspace.FakeSessionNotification
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class RealRootComponentRoutingTest {
    private val sessionController = FakeSessionNotification()
    private val secureStorage = FakeSecureStorage()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsOnHomeWithoutATicket() {
        assertIs<RootComponent.Child.Home>(root().activeChild())
    }

    @Test
    fun restoredSessionReopensTheWorkspace() = runTest {
        SessionCredentialStore(secureStorage).writeSecureActiveSession(
            StoredSession(
                artifact = TICKET.toStoredArtifact(),
                sessionId = "session-1",
                projectName = "Sample",
                projectPath = "",
                startedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )

        val root = root()

        assertIs<RootComponent.Child.Workspace>(root.activeChild())
    }

    @Test
    fun sessionEndedFromTheNotificationLeavesTheWorkspace() = runTest {
        val root = root()

        sessionController.endSessionRequests.emit(TICKET.toStoredArtifact())

        val stack = root.childStack.value.items.map { it.instance }
        assertIs<RootComponent.Child.Home>(stack.first())
        assertTrue(stack.none { it is RootComponent.Child.Workspace })
    }

    @Test
    fun homeOpensSettings() {
        val root = root()

        root.home().component.onIntent(HomeIntent.OpenSettings)

        assertIs<RootComponent.Child.Settings>(root.activeChild())
    }

    @Test
    fun boundLocalWorkspaceOpensCreateCloneAndWorkspaceScreens() {
        val root = root(localWorkspace = StubLocalWorkspace)

        requireNotNull(root.home().local).onIntent(LocalHomeIntent.OpenCreate)
        assertIs<RootComponent.Child.CreateWorkspace>(root.activeChild())

        root.onBack()
        requireNotNull(root.home().local).onIntent(LocalHomeIntent.OpenClone)
        assertIs<RootComponent.Child.CloneWorkspace>(root.activeChild())

        root.onBack()
        requireNotNull(root.home().local).onIntent(LocalHomeIntent.OpenWorkspace("notes"))
        assertIs<RootComponent.Child.LocalWorkspace>(root.activeChild())

        root.onBack()
        root.home().component.onIntent(HomeIntent.OpenSettings)
        assertNotNull(assertIs<RootComponent.Child.Settings>(root.activeChild()).git)
    }

    @Test
    fun unboundLocalWorkspaceKeepsHome() {
        val root = root(localWorkspace = null)

        assertIs<RootComponent.Child.Home>(root.activeChild())
        assertNull(root.home().local)
        root.home().component.onIntent(HomeIntent.OpenSettings)
        assertNull(assertIs<RootComponent.Child.Settings>(root.activeChild()).git)
    }

    private fun root(initialTicket: String? = null, localWorkspace: LocalWorkspaceEntry? = null): RootComponent {
        val lifecycle = LifecycleRegistry()
        val settings = MapSettings()
        return RealRootComponent(
            componentContext = DefaultComponentContext(lifecycle),
            sessions = testLinkSessions(secureStorage, sessionController),
            terminalPreferences = TerminalPreferences(settings),
            knownHostDiscovery = testKnownHostDiscovery(),
            appSettingsStore = AppSettingsStore(settings, dynamicColorAvailable = false),
            sessionStore = SessionCredentialStore(secureStorage),
            sessionController = sessionController,
            localWorkspace = localWorkspace,
            discoveryGateway = NoopLocalNetworkDiscoveryGateway,
            pairingProbe = PairingProbe(http = unreachableLinkHttp()),
        ).also { lifecycle.create() }
    }

    private fun RootComponent.activeChild(): RootComponent.Child = childStack.value.active.instance

    private fun RootComponent.home() = childStack.value.items.first().instance as RootComponent.Child.Home

    /** Navigation only: every child is an inert stand-in that goes back when asked. */
    private object StubLocalWorkspace : LocalWorkspaceEntry {
        override fun home(
            componentContext: ComponentContext,
            onOpenWorkspace: (slug: String) -> Unit,
            onOpenCreate: () -> Unit,
            onOpenClone: () -> Unit,
        ) = object : LocalHomeComponent {
            override val state: StateFlow<LocalHomeState> = MutableStateFlow(LocalHomeState())
            override fun onIntent(intent: LocalHomeIntent) = when (intent) {
                is LocalHomeIntent.OpenWorkspace -> onOpenWorkspace(intent.slug)
                LocalHomeIntent.OpenCreate -> onOpenCreate()
                LocalHomeIntent.OpenClone -> onOpenClone()
                is LocalHomeIntent.DeleteWorkspace -> Unit
            }
        }

        override fun workspace(componentContext: ComponentContext, slug: String, onBack: () -> Unit) =
            object : LocalWorkspaceComponent {
                override val state: StateFlow<LocalWorkspaceState> = MutableStateFlow(LocalWorkspaceState(slug))
                override val effects: Flow<LocalWorkspaceEffect> = emptyFlow()
                override val git: GitPaneComponent get() = error("unused")
                override fun onIntent(intent: LocalWorkspaceIntent) = Unit
                override fun onBack() = onBack.invoke()
            }

        override fun create(componentContext: ComponentContext, onBack: () -> Unit, onCreated: (slug: String) -> Unit) =
            object : CreateWorkspaceComponent {
                override val state: StateFlow<CreateWorkspaceState> = MutableStateFlow(CreateWorkspaceState())
                override fun onIntent(intent: CreateWorkspaceIntent) = Unit
                override fun onBack() = onBack.invoke()
            }

        override fun clone(componentContext: ComponentContext, onBack: () -> Unit, onCloned: (slug: String) -> Unit) =
            object : CloneWorkspaceComponent {
                override val state: StateFlow<CloneWorkspaceState> = MutableStateFlow(CloneWorkspaceState())
                override fun onIntent(intent: CloneWorkspaceIntent) = Unit
                override fun onBack() = onBack.invoke()
            }

        override fun gitSettings(componentContext: ComponentContext) = object : GitSettingsComponent {
            override val state: StateFlow<GitSettingsState> = MutableStateFlow(GitSettingsState())
            override fun onIntent(intent: GitSettingsIntent) = Unit
        }
    }

    private companion object {
        val TICKET = LinkTicket(
            endpoint = "https://127.0.0.1:63342",
            sessionId = "session-1",
            token = "token-1",
            fingerprint = "fingerprint-1",
            projectName = "Sample",
            projectPath = "",
        )
    }
}

/** The probe reaches nothing in these tests: routing, not pairing, is what is under test. */
private fun unreachableLinkHttp(): LinkHttp =
    LinkHttp(
        httpClient = HttpClient(MockEngine) {
            engine { addHandler { throw IllegalStateException("host unreachable") } }
        },
        deviceIdentity = DeviceIdentity(),
    )
