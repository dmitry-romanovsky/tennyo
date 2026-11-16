package app.tennyo.mobile.feature.launch

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkPush
import app.tennyo.mobile.design.PaneNotice
import app.tennyo.mobile.design.failureText
import app.tennyo.mobile.link.pane.PaneLink
import app.tennyo.mobile.link.pane.PaneRequests
import app.tennyo.mobile.link.pane.SyncPass
import app.tennyo.mobile.link.pane.WorkspacePane
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Keeps launch configuration state in sync with [link] and handles launch actions. */
class RealLaunchPaneComponent(
    componentContext: ComponentContext,
    private val link: PaneLink,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : LaunchPaneComponent, WorkspacePane, ComponentContext by componentContext {
    private val store = FeatureStore<LaunchState, LaunchMsg, PaneNotice>(
        LaunchState(),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private val outputStore = FeatureStore<LaunchOutputState, LaunchOutputMsg, Nothing>(
        LaunchOutputState(),
        lifecycle,
        ::reduceOutput,
        mainContext,
    )
    private val client = LaunchClient(link)
    private val requests = PaneRequests<LaunchRequest>()
    private val scope get() = store.scope

    override val state: StateFlow<LaunchState> = store.state
    override val output: StateFlow<LaunchOutputState> = outputStore.state
    override val notices: Flow<PaneNotice> = store.effects

    private val connected: Boolean get() = link.ticket.value != null

    override fun onIntent(intent: LaunchIntent) {
        if (!connected) return
        when (intent) {
            is LaunchIntent.SelectConfiguration -> selectConfiguration(intent.configurationId)
            is LaunchIntent.Start -> startLaunch(intent.configurationId)

            is LaunchIntent.Stop -> launchAction(LaunchAction.StopLaunch) {
                client.stopLaunchConfiguration(intent.configurationId)
            }
        }
    }

    // WorkspacePane ---------------------------------------------------------------------------

    override suspend fun loadSnapshot(shown: Boolean) {
        if (!shown || !connected || Capability.LAUNCH_CONFIGURATIONS !in link.project.value.capabilities) return
        loadConfigurations()
    }

    override suspend fun sync(pass: SyncPass) {
        if (!pass.shown) return
        val due = requests.allowed(
            if (pass.metadata) setOf(LaunchRequest.Configurations) else emptySet(),
            pass.nowEpochMillis,
        )
        if (LaunchRequest.Configurations in due) refreshConfigurations()
    }

    override fun apply(push: LinkPush) {
        when (push) {
            is LinkPush.LaunchConfigurations -> store.dispatch(LaunchMsg.ConfigurationsReceived(push.state))
            is LinkPush.LaunchOutputChanged -> outputStore.dispatch(LaunchOutputMsg.OutputReceived(push.output))
            else -> Unit
        }
    }

    override fun onShown() {
        if (connected) scope.launch { sync(SyncPass.userRefresh()) }
    }

    private fun startLaunch(configurationId: String) {
        outputStore.dispatch(LaunchOutputMsg.LaunchStarted(configurationId))
        store.dispatch(LaunchMsg.StartRequested(configurationId))
        scope.launch {
            try {
                runCatching {
                    client.startLaunchConfiguration(configurationId)
                    loadConfigurations()
                }.onFailure { throwable ->
                    notifyFailure(LaunchAction.StartLaunch, throwable, sticky = true)
                }
            } finally {
                store.dispatch(LaunchMsg.StartFinished(configurationId))
            }
        }
    }

    private fun selectConfiguration(configurationId: String) {
        outputStore.dispatch(LaunchOutputMsg.ConfigurationSelected(configurationId))
    }

    /** Starts or stops a launch and immediately refreshes its status. */
    private fun launchAction(action: LaunchAction, request: suspend () -> Unit) {
        scope.launch {
            runCatching {
                request()
                loadConfigurations()
            }.onFailure { notifyFailure(action, it, sticky = true) }
        }
    }

    private suspend fun loadConfigurations() {
        runCatching { client.getLaunchConfigurations() }
            .onSuccess { store.dispatch(LaunchMsg.ConfigurationsReceived(it)) }
            .onFailure { notifyFailure(LaunchAction.LoadLaunchConfigurations, it) }
    }

    // Background refresh -----------------------------------------------------------------------

    private suspend fun refreshConfigurations() {
        requests.run(LaunchRequest.Configurations, "Unable to refresh launch configurations from fallback sync") {
            client.getLaunchConfigurations()
        }?.let { store.dispatch(LaunchMsg.ConfigurationsReceived(it)) }
    }

    private fun notifyFailure(action: LaunchAction, throwable: Throwable, sticky: Boolean = false) {
        if (throwable is CancellationException) throw throwable
        Logger.w("Launch action $action failed", throwable)
        store.emit(PaneNotice.Failure(failureText(action.failure, throwable.message), sticky))
    }

}
