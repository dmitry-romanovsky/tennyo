package app.tennyo.contract

import kotlinx.serialization.Serializable

@Serializable
data class LaunchConfiguration(
    val id: String,
    val name: String,
    val type: String,
    val folder: String? = null,
    val isRunning: Boolean = false,
)

@Serializable
data class LaunchConfigurationsState(
    val configurations: List<LaunchConfiguration>,
    val selectedConfigurationId: String? = null,
)

@Serializable
data class LaunchCandidate(
    val configurationId: String,
    val name: String,
    val type: String,
)

@Serializable
data class LaunchOutput(
    val configurationId: String,
    val tabId: String? = null,
    val text: String,
    val isRunning: Boolean,
)

@Serializable
data class LaunchTab(
    val id: String,
    val configurationId: String? = null,
    val title: String,
    val isRunning: Boolean = false,
    val isSelected: Boolean = false,
)

@Serializable
data class LaunchOutputSnapshot(
    val tabId: String,
    val configurationId: String? = null,
    val text: String,
    val isRunning: Boolean,
)

@Serializable
data class LaunchTabsState(
    val tabs: List<LaunchTab> = emptyList(),
    val selectedLaunchTabId: String? = null,
)
