package app.tennyo.mobile.feature.connect

import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.session_start_rejected
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.debugShortIdOrNone
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.discoveryInfo
import app.tennyo.mobile.link.discoveryWarn
import app.tennyo.mobile.link.pairing.DiscoveryConnectResult
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.KnownHostTarget
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.pairing.PairingTarget
import app.tennyo.mobile.link.pane.nowEpochMillis
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.store.FeatureStore
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnPause
import com.arkivanov.essenty.lifecycle.doOnResume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RealConnectComponent(
    componentContext: ComponentContext,
    private val discovery: KnownHostDiscovery,
    private val gateway: LocalNetworkDiscoveryGateway,
    private val pairingProbe: PairingProbe,
    private val onSessionTicket: (LinkTicket) -> Unit,
    private val onPairingHostSelected: (PairingTarget) -> Unit,
    private val onBackNav: () -> Unit,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    now: () -> Long = ::nowEpochMillis,
) : ConnectComponent, ComponentContext by componentContext {
    private val store = FeatureStore<ConnectState, ConnectMsg, ConnectEffect>(
        initialState = ConnectState(),
        lifecycle = lifecycle,
        reduce = ::reduceConnect,
    )
    override val state: StateFlow<ConnectState> = store.state
    override val effects: Flow<ConnectEffect> = store.effects

    private val loop = PairingDiscoveryLoop(
        store = store,
        discovery = discovery,
        gateway = gateway,
        probe = pairingProbe,
        backgroundDispatcher = backgroundDispatcher,
        onTargetsFound = { targets -> targets.forEach { probedTargets[it.hostShortId] = it } },
        now = now,
    )
    private val addresses = KnownHostAddresses(store, discovery, backgroundDispatcher)
    private val probedTargets = mutableMapOf<String, PairingTarget>()
    private var startDiscoverySessionJob: Job? = null

    init {
        // The loop runs only while the screen is on screen; nothing probes from a background service.
        lifecycle.doOnResume {
            discoveryDebug { "pairing screen resumed" }
            loop.start()
        }
        lifecycle.doOnPause {
            discoveryDebug { "pairing screen paused" }
            loop.stop()
        }
        lifecycle.doOnDestroy { loop.stop() }
    }

    override fun onIntent(intent: ConnectIntent) {
        when (intent) {
            is ConnectIntent.PairingHostSelected -> selectPairingHost(intent.shortId)
            is ConnectIntent.StartSessionAndConnectClicked -> startKnownHostSession(intent.targetId)
            is ConnectIntent.ForgetKnownHostClicked -> forgetKnownHost(intent.targetId)
            is ConnectIntent.ProjectSelectionConfirmed -> continueKnownHostSessionStart(
                targetId = intent.targetId,
                projectId = intent.projectId,
            )

            ConnectIntent.ProjectSelectionDismissed -> dismissProjectSelection()
            is ConnectIntent.AddressesClicked -> addresses.open(intent.targetId)
            is ConnectIntent.AddressInputChanged -> store.dispatch(ConnectMsg.Addresses.InputChanged(intent.text))
            ConnectIntent.AddressSubmitted -> addresses.submit()
            is ConnectIntent.AddressRemoveClicked -> addresses.remove(intent.endpoint)
            ConnectIntent.AddressesDismissed -> store.dispatch(ConnectMsg.Addresses.Closed)
            ConnectIntent.DiscoveryErrorDismissed -> {
                discoveryDebug { "discovery error dismissed" }
                store.dispatch(ConnectMsg.Discovery.ErrorDismissed)
            }

            is ConnectIntent.NotificationPermissionStatusLoaded ->
                store.dispatch(ConnectMsg.Permission.NotificationChanged(intent.status.isGranted))

            ConnectIntent.NotificationPermissionInfoClicked ->
                store.dispatch(ConnectMsg.Permission.NotificationExplanationShown(visible = true))

            ConnectIntent.NotificationPermissionRequestDismissed ->
                store.dispatch(ConnectMsg.Permission.NotificationExplanationShown(visible = false))

            ConnectIntent.NotificationPermissionRequestConfirmed -> {
                store.dispatch(ConnectMsg.Permission.NotificationExplanationShown(visible = false))
                store.emit(ConnectEffect.RequestNotificationPermission)
            }

            is ConnectIntent.NotificationPermissionRequestResult -> {
                store.dispatch(ConnectMsg.Permission.NotificationChanged(intent.isGranted))
                store.dispatch(ConnectMsg.Permission.NotificationExplanationShown(visible = false))
            }
        }
    }

    override fun onBack() {
        onBackNav()
    }

    private fun dismissProjectSelection() {
        discoveryInfo("[discovery] User cancelled connection")
        discoveryDebug { "project selection dismissed" }
        startDiscoverySessionJob?.let { job ->
            discoveryDebug { "cancelling in-flight discovery session start" }
            job.cancel()
            startDiscoverySessionJob = null
        }
        store.dispatch(ConnectMsg.KnownHost.SessionStartEnded(failure = null))
    }

    /** Hands the picked host to the pairing flow with the fingerprint this device observed for it. */
    private fun selectPairingHost(shortId: String) {
        val target = probedTargets[shortId] ?: run {
            discoveryDebug { "pairing host tapped but its offer is gone shortId=${shortId.debugShortId()}" }
            return
        }
        discoveryInfo("[pairing] User picked ${target.hostName} (${target.hostShortId})")
        store.dispatch(ConnectMsg.Pairing.HostSelected(shortId))
        onPairingHostSelected(target)
    }

    private fun startKnownHostSession(targetId: String) {
        val target = discovery.knownHostTargets().firstOrNull { it.companionId == targetId } ?: run {
            discoveryDebug { "discovery requested for unknown companion=${targetId.debugShortId()}" }
            return
        }
        discoveryInfo("[discovery] User initiated connection for ${target.companionId.debugShortId()}")
        discoveryDebug {
            "discovery requested companion=${target.companionId.debugShortId()} " +
                "endpoint=${target.lastResolvedHost.debugQuotedOrNone()} " +
                "lastProject=${target.lastProjectName.debugQuotedOrNone()}"
        }
        store.dispatch(ConnectMsg.KnownHost.Selected(targetId))
        store.scope.launch {
            val discoverResult = runCatchingCancellable { discovery.discoverCandidate(target) }
            val answer = discoverResult.getOrNull()
            val discoverResponse = answer?.response
            val challenge = discoverResponse?.challenge
            discoveryDebug {
                "discover response companion=${target.companionId.debugShortId()} status=${discoverResponse?.status} " +
                    "challenge=${challenge?.challengeId.debugShortIdOrNone()}"
            }
            if (answer == null || challenge == null || answer.response.status != DiscoveryStatus.AVAILABLE) {
                discoveryDebug {
                    "discovery aborted because target is unavailable " +
                        "companion=${target.companionId.debugShortId()} status=${discoverResponse?.status}"
                }
                store.dispatch(
                    ConnectMsg.KnownHost.SessionStartEnded(
                        connectError(ConnectFailure.KnownHostUnavailable, discoverResult.exceptionOrNull()),
                    ),
                )
                    return@launch
            }
            if (state.value.selectedDiscoveryTargetId != target.companionId) {
                discoveryDebug {
                    "discover result ignored because selection changed companion=${target.companionId.debugShortId()}"
                }
                return@launch
            }
            val resolvedTarget = target.withDescriptor(answer.response.descriptor)
            val projectChoices = answer.response.projectChoices
            if (projectChoices.size > 1) {
                discoveryInfo(
                    "[discovery] Project selection shown for ${resolvedTarget.companionId.debugShortId()} " +
                        "choices=${projectChoices.size}"
                )
                store.dispatch(
                    ConnectMsg.KnownHost.ProjectSelectionRequested(
                        ProjectSelectionUiState(
                            targetId = resolvedTarget.companionId,
                            companionLabel = resolvedTarget.companionLabel,
                            projects = projectChoices.map { choice -> choice.toUiState() },
                        ),
                    ),
                )
                return@launch
            }
            continueKnownHostSessionStart(
                target = resolvedTarget,
                endpoint = answer.endpoint,
                challenge = challenge,
                projectId = projectChoices.singleOrNull()?.projectId,
            )
        }
    }

    private fun forgetKnownHost(targetId: String) {
        discoveryDebug { "forget known ide requested companion=${targetId.debugShortId()}" }
        store.dispatch(ConnectMsg.KnownHost.Forgotten(targetId))
        store.scope.launch {
            runCatching {
                discovery.forgetKnownHostTarget(targetId)
            }.onFailure { throwable ->
                discoveryDebug {
                    "forget known ide failed companion=${targetId.debugShortId()} " +
                        "error=${throwable.message.debugQuotedOrNone()}"
                }

            }
        }
    }

    private fun continueKnownHostSessionStart(targetId: String, projectId: String?) {
        if (state.value.selectedDiscoveryTargetId != targetId) return
        val target = discovery.knownHostTargets().firstOrNull { it.companionId == targetId } ?: return
        discoveryDebug {
            "discovery session start companion=${target.companionId.debugShortId()} " +
                "projectId=${projectId.debugQuotedOrNone()}"
        }
        discoveryInfo(
            "[discovery] Starting session for ${target.companionId.debugShortId()} " +
                "project=${projectId.debugQuotedOrNone()}"
        )
        store.dispatch(ConnectMsg.KnownHost.SessionStartStarted)
        startDiscoverySessionJob = store.scope.launch {
            try {
                val discoverResult = runCatchingCancellable { discovery.discoverCandidate(target) }
                val answer = discoverResult.getOrNull()
                val discoverResponse = answer?.response
                val challenge = discoverResponse?.challenge
                if (answer == null || challenge == null || answer.response.status != DiscoveryStatus.AVAILABLE) {
                    store.dispatch(
                        ConnectMsg.KnownHost.SessionStartEnded(
                            connectError(ConnectFailure.KnownHostUnavailable, discoverResult.exceptionOrNull()),
                        ),
                    )
                            return@launch
                }
                continueKnownHostSessionStart(
                    target = target.withDescriptor(answer.response.descriptor),
                    endpoint = answer.endpoint,
                    challenge = challenge,
                    projectId = projectId,
                )
            } finally {
                if (startDiscoverySessionJob?.isCompleted != true) {
                    startDiscoverySessionJob = null
                }
            }
        }
    }

    private suspend fun continueKnownHostSessionStart(
        target: KnownHostTarget,
        endpoint: String,
        challenge: DiscoveryChallenge,
        projectId: String?,
    ) {
        if (state.value.selectedDiscoveryTargetId != target.companionId) {
            discoveryDebug {
                "discovery connect ignored because selection changed companion=${target.companionId.debugShortId()}"
            }
            return
        }
        val startSessionCatch = runCatchingCancellable {
            discovery.startDiscoverySession(target, challenge, endpoint, projectId)
        }
        val startSessionException = startSessionCatch.exceptionOrNull()
        startSessionException?.let { throwable ->
            discoveryDebug {
                "discovery connect threw exception companion=${target.companionId.debugShortId()} " +
                    "projectId=${projectId.debugQuotedOrNone()} error=${throwable.message.debugQuotedOrNone()}"
            }
        }
        when (val connectResult = startSessionCatch.getOrNull()) {
            is DiscoveryConnectResult.Success -> {
                val artifact = connectResult.artifact
                discoveryDebug {
                    "discovery connect succeeded companion=${target.companionId.debugShortId()} " +
                        "session=${artifact.sessionId.debugShortId()} endpoint=${artifact.endpoint.debugQuotedOrNone()}"
                }
                discoveryInfo(
                    "[discovery] Session started for ${target.companionId.debugShortId()} " +
                        "session=${artifact.sessionId.debugShortId()}"
                )
                startDiscoverySessionJob = null
                store.dispatch(ConnectMsg.KnownHost.SessionStartEnded(failure = null))
                onSessionTicket(artifact)
            }

            is DiscoveryConnectResult.Failed -> {
                discoveryWarn(
                    "[discovery] Session start failed for ${target.companionId.debugShortId()} " +
                        "status=${connectResult.status} blocked=${connectResult.blockedMessage.debugQuotedOrNone()}"
                )
                store.dispatch(
                    ConnectMsg.KnownHost.SessionStartEnded(
                        ConnectErrorUiState(
                            failure = ConnectFailure.SessionStartFailed,
                            details = connectResult.blockedMessage?.takeIf { it.isNotBlank() }?.let(UiText::Verbatim)
                                ?: UiText.Resource(Res.string.session_start_rejected, connectResult.status.name),
                        ),
                    ),
                )
            }

            null -> {
                discoveryWarn(
                    "[discovery] Session start threw exception for ${target.companionId.debugShortId()} " +
                        "error=${startSessionException?.message.debugQuotedOrNone()}"
                )
                store.dispatch(
                    ConnectMsg.KnownHost.SessionStartEnded(
                        connectError(ConnectFailure.SessionStartFailed, startSessionException),
                    ),
                )
            }
        }
    }

}
