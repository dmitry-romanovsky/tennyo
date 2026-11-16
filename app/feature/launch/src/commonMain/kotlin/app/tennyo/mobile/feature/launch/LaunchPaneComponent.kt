package app.tennyo.mobile.feature.launch

import app.tennyo.mobile.design.PaneNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Lists launch configurations and starts or stops them. */
interface LaunchPaneComponent {
    val state: StateFlow<LaunchState>
    val output: StateFlow<LaunchOutputState>

    /** Consumed by the workspace, which shows them in its message area and banner. */
    val notices: Flow<PaneNotice>

    fun onIntent(intent: LaunchIntent)
}

sealed interface LaunchIntent {
    data class SelectConfiguration(val configurationId: String) : LaunchIntent
    data class Start(val configurationId: String) : LaunchIntent
    data class Stop(val configurationId: String) : LaunchIntent
}
