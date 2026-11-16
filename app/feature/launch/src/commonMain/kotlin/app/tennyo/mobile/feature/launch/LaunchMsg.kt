package app.tennyo.mobile.feature.launch

import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.contract.LaunchOutput

/** Every change to [LaunchState]; applied only through [reduce]. */
internal sealed interface LaunchMsg {
    data class ConfigurationsReceived(val configurations: LaunchConfigurationsState) : LaunchMsg
    data class StartRequested(val configurationId: String) : LaunchMsg
    data class StartFinished(val configurationId: String) : LaunchMsg
}

internal sealed interface LaunchOutputMsg {
    data class ConfigurationSelected(val configurationId: String) : LaunchOutputMsg
    data class LaunchStarted(val configurationId: String) : LaunchOutputMsg
    data class OutputReceived(val output: LaunchOutput) : LaunchOutputMsg
}

internal fun reduce(state: LaunchState, msg: LaunchMsg): LaunchState = when (msg) {
    is LaunchMsg.ConfigurationsReceived -> state.copy(configurations = msg.configurations.configurations)
    is LaunchMsg.StartRequested -> state.copy(startingConfigurationId = msg.configurationId)
    is LaunchMsg.StartFinished -> state.copy(
        startingConfigurationId = state.startingConfigurationId.takeUnless { it == msg.configurationId },
    )
}

internal fun reduceOutput(state: LaunchOutputState, msg: LaunchOutputMsg): LaunchOutputState = when (msg) {
    is LaunchOutputMsg.ConfigurationSelected ->
        if (state.configurationId == msg.configurationId) {
            state
        } else {
            LaunchOutputState(configurationId = msg.configurationId)
        }
    is LaunchOutputMsg.LaunchStarted -> LaunchOutputState(configurationId = msg.configurationId)
    is LaunchOutputMsg.OutputReceived -> {
        if (msg.output.configurationId != state.configurationId) {
            state
        } else {
            val combined = state.text + msg.output.text
            state.copy(text = combined.takeLast(MAX_LAUNCH_OUTPUT_CHARS))
        }
    }
}
