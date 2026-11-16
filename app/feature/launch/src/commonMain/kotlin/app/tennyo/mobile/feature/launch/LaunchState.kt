package app.tennyo.mobile.feature.launch

import app.tennyo.contract.LaunchConfiguration

/** The configurations available to launch on the connected host. */
data class LaunchState(
    val configurations: List<LaunchConfiguration> = emptyList(),
    val startingConfigurationId: String? = null,
)

data class LaunchOutputState(
    val configurationId: String? = null,
    val text: String = "",
)

internal const val MAX_LAUNCH_OUTPUT_CHARS = 80_000

enum class LaunchRequest {
    Configurations,
}
