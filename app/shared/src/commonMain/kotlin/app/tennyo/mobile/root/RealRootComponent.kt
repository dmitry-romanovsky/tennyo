package app.tennyo.mobile.root

import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.feature.connect.ActiveLinkSummary
import app.tennyo.mobile.feature.connect.RealConnectComponent
import app.tennyo.mobile.feature.connect.RealHomeComponent
import app.tennyo.mobile.feature.local.LocalWorkspaceEntry
import app.tennyo.mobile.feature.settings.AppSettingsState
import app.tennyo.mobile.feature.settings.AppSettingsStore
import app.tennyo.mobile.feature.settings.RealSettingsComponent
import app.tennyo.mobile.feature.terminal.TerminalPreferences
import app.tennyo.mobile.link.LinkSessions
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.pairing.PairingTarget
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.session.StoredSessionReadResult
import app.tennyo.mobile.link.session.toLinkTicket
import app.tennyo.mobile.workspace.RealWorkspaceComponent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandler
import com.arkivanov.essenty.lifecycle.doOnCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * App navigation for every platform. A link session is only entered with a ticket this process accepted — from the
 * pairing screen or the securely restored session — so a stale back-stack entry cannot reopen a session.
 */
@OptIn(DelicateDecomposeApi::class)
class RealRootComponent(
    componentContext: ComponentContext,
    private val sessions: LinkSessions,
    private val terminalPreferences: TerminalPreferences,
    private val knownHostDiscovery: KnownHostDiscovery,
    private val appSettingsStore: AppSettingsStore,
    private val sessionStore: SessionCredentialStore,
    private val sessionController: SessionNotification,
    private val localWorkspace: LocalWorkspaceEntry?,
    private val discoveryGateway: LocalNetworkDiscoveryGateway,
    private val pairingProbe: PairingProbe,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val acceptedTickets = mutableSetOf<LinkTicket>()
    private var activeTicket: LinkTicket? = null
    private val activeLinkSummary = MutableStateFlow<ActiveLinkSummary?>(null)
    private val mutableAppSettings = MutableStateFlow(appSettingsStore.load())

    override val backHandler: BackHandler = componentContext.backHandler
    override val appSettings: StateFlow<AppSettingsState> = mutableAppSettings.asStateFlow()

    override val childStack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Home,
            handleBackButton = false,
            childFactory = ::createChild,
        )

    init {
        lifecycle.doOnCreate {
            observeSessionEndRequests()
            restoreSecureSession()
        }
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onBack() {
        when (val child = childStack.value.active.instance) {
            is RootComponent.Child.Home -> Unit
            is RootComponent.Child.Connect -> child.component.onBack()
            is RootComponent.Child.Workspace -> child.component.onBack()
            is RootComponent.Child.Settings -> child.component.onBack()
            is RootComponent.Child.LocalWorkspace -> child.component.onBack()
            is RootComponent.Child.CreateWorkspace -> child.component.onBack()
            is RootComponent.Child.CloneWorkspace -> child.component.onBack()
        }
    }

    private fun createChild(config: Config, context: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Home -> homeChild(context)
            Config.Connect -> activeTicket?.let { workspaceChild(context, it) } ?: connectChild(context)
            is Config.Workspace -> activeTicket?.let { workspaceChild(context, it) } ?: homeChild(context)
            Config.Settings -> settingsChild(context)
            is Config.LocalWorkspace -> localChild(context) { entry ->
                RootComponent.Child.LocalWorkspace(entry.workspace(context, config.slug, onBack = navigation::pop))
            }

            Config.CreateWorkspace -> localChild(context) { entry ->
                RootComponent.Child.CreateWorkspace(
                    entry.create(context, onBack = navigation::pop, onCreated = ::replaceWithLocalWorkspace),
                )
            }

            Config.CloneWorkspace -> localChild(context) { entry ->
                RootComponent.Child.CloneWorkspace(
                    entry.clone(context, onBack = navigation::pop, onCloned = ::replaceWithLocalWorkspace),
                )
            }
        }

    private fun homeChild(context: ComponentContext): RootComponent.Child.Home =
        RootComponent.Child.Home(
            RealHomeComponent(
                componentContext = context,
                activeSession = activeLinkSummary,
                onOpenConnect = ::openLinkEntry,
                onOpenSettings = { navigation.push(Config.Settings) },
                onEndSession = ::endActiveSession,
            ),
            localWorkspace?.home(
                componentContext = context.childContext("localHome"),
                onOpenWorkspace = { slug -> navigation.push(Config.LocalWorkspace(slug)) },
                onOpenCreate = { navigation.push(Config.CreateWorkspace) },
                onOpenClone = { navigation.push(Config.CloneWorkspace) },
            ),
        )

    private fun connectChild(context: ComponentContext): RootComponent.Child.Connect =
        RootComponent.Child.Connect(
            RealConnectComponent(
                componentContext = context,
                discovery = knownHostDiscovery,
                gateway = discoveryGateway,
                pairingProbe = pairingProbe,
                onSessionTicket = ::openSession,
                onPairingHostSelected = ::openSession,
                onBackNav = navigation::pop,
            ),
        )

    /** A remembered host answered with a ticket: the session runs under it. */
    private fun openSession(ticket: LinkTicket) {
        acceptedTickets += ticket
        setActiveTicket(ticket)
        navigation.replaceAll(Config.Home, Config.Workspace(ticket.sessionId))
    }

    /**
     * A host in pairing mode was picked. The window's credential stands in for a session token until the user taps
     * the words; the confirmation then hands back the real ticket, and the workspace switches onto it.
     */
    private fun openSession(target: PairingTarget) {
        val provisional = target.toProvisionalTicket()
        acceptedTickets += provisional
        setActiveTicket(provisional)
        navigation.replaceAll(Config.Home, Config.Workspace(provisional.sessionId))
    }

    private fun workspaceChild(context: ComponentContext, ticket: LinkTicket): RootComponent.Child {
        val validTicket = acceptedTicketOrNull(ticket)
            ?: return homeChild(context).also {
                context.lifecycle.doOnCreate {
                    setActiveTicket(null)
                    navigation.replaceAll(Config.Home)
                }
            }
        return RootComponent.Child.Workspace(
            RealWorkspaceComponent(
                componentContext = context,
                session = sessions.open(validTicket),
                terminalPreferences = terminalPreferences,
                sessionTicket = validTicket,
                onBackNav = navigation::pop,
                onTicketUpdated = { updated ->
                    // The session re-issues its ticket once the host confirms an endpoint; it is as trusted as the
                    // one it replaces.
                    acceptedTickets += updated
                    setActiveTicket(updated)
                },
                onSessionEnded = {
                    setActiveTicket(null)
                    navigation.replaceAll(Config.Home, Config.Connect)
                },
            ),
        )
    }

    private fun settingsChild(context: ComponentContext): RootComponent.Child.Settings =
        RootComponent.Child.Settings(
            RealSettingsComponent(
                componentContext = context,
                initialState = mutableAppSettings.value,
                onThemeModeChanged = ::setThemeMode,
                onColorSchemeChanged = ::setColorScheme,
                onEditorSoftWrapChanged = ::setEditorSoftWrap,
                onBackNav = navigation::pop,
            ),
            terminalPreferences,
            localWorkspace?.gitSettings(context.childContext("gitSettings")),
        )

    /** Local routes restored on a platform without the local feature land on Home instead. */
    private fun localChild(
        context: ComponentContext,
        create: (LocalWorkspaceEntry) -> RootComponent.Child,
    ): RootComponent.Child =
        localWorkspace?.let(create) ?: homeChild(context).also {
            context.lifecycle.doOnCreate { navigation.replaceAll(Config.Home) }
        }

    private fun replaceWithLocalWorkspace(slug: String) {
        navigation.pop()
        navigation.push(Config.LocalWorkspace(slug))
    }

    private fun openLinkEntry() {
        val activeValid = activeTicket?.let(::acceptedTicketOrNull)
        if (activeValid != null) {
            navigation.push(Config.Workspace(activeValid.sessionId))
        } else {
            setActiveTicket(null)
            navigation.push(Config.Connect)
        }
    }

    private fun observeSessionEndRequests() {
        sessionController.endSessionRequests
            .onEach { endedTicket ->
                if (isActiveTicket(endedTicket)) {
                    setActiveTicket(null)
                    navigation.replaceAll(Config.Home)
                }
            }
            .launchIn(scope)
    }

    private fun restoreSecureSession() {
        scope.launch {
            val restored = sessionStore.readSecureActiveSession()
            val ticket = (restored as? StoredSessionReadResult.Found)?.record?.artifact?.toLinkTicket()
            if (ticket != null && activeTicket == null) {
                acceptedTickets += ticket
                setActiveTicket(ticket)
                navigation.replaceAll(Config.Home, Config.Workspace(ticket.sessionId))
            }
        }
    }

    private fun acceptedTicketOrNull(ticket: LinkTicket): LinkTicket? = ticket.takeIf { it in acceptedTickets }

    /** The notification ends a session by its ticket, which may be an older rendering of the active one. */
    private fun isActiveTicket(artifact: String): Boolean {
        val active = activeTicket ?: return artifact.isBlank()
        val ended = artifact.toLinkTicket() ?: return false
        return ended.sessionId == active.sessionId && ended.token == active.token
    }

    private fun setActiveTicket(ticket: LinkTicket?) {
        val previous = activeTicket
        activeTicket = ticket
        if (ticket == null) {
            scope.launch { sessionStore.clearSecureActiveSession() }
        }
        activeLinkSummary.value = ticket?.let { active ->
            ActiveLinkSummary(
                hostLabel = active.hostVersion?.takeIf { it.isNotBlank() } ?: active.projectName,
                // A refreshed ticket for the same session keeps its start.
                startedAtEpochMillis = activeLinkSummary.value
                    ?.takeIf { previous?.sessionId == active.sessionId }
                    ?.startedAtEpochMillis
                    ?: Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    /** Home's stop: the notification's End path tells the host, and the ticket is dropped here at once. */
    private fun endActiveSession() {
        sessionController.endSession()
        setActiveTicket(null)
    }

    private fun setThemeMode(themeMode: AppThemeMode): AppSettingsState {
        appSettingsStore.saveThemeMode(themeMode)
        return reloadAppSettings()
    }

    private fun setColorScheme(colorScheme: AppColorScheme): AppSettingsState {
        appSettingsStore.saveColorScheme(colorScheme)
        return reloadAppSettings()
    }

    private fun setEditorSoftWrap(softWrap: Boolean): AppSettingsState {
        appSettingsStore.saveEditorSoftWrap(softWrap)
        return reloadAppSettings()
    }

    private fun reloadAppSettings(): AppSettingsState =
        appSettingsStore.load().also { mutableAppSettings.value = it }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Home : Config

        @Serializable
        data object Connect : Config

        @Serializable
        data class Workspace(val sessionId: String) : Config

        @Serializable
        data object Settings : Config

        @Serializable
        data class LocalWorkspace(val slug: String) : Config

        @Serializable
        data object CreateWorkspace : Config

        @Serializable
        data object CloneWorkspace : Config
    }
}
