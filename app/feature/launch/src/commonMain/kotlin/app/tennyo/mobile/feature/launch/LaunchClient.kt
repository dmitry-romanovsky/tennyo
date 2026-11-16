package app.tennyo.mobile.feature.launch

import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.request

/** Launch configuration requests sent to the host. */
internal class LaunchClient(private val link: LinkCalls) {
    suspend fun getLaunchConfigurations(): LaunchConfigurationsState =
        link.request<LinkResult.LaunchConfigurations>(LinkCall.LaunchConfigurations).state

    suspend fun startLaunchConfiguration(id: String) {
        link.call(LinkCall.StartLaunch(id))
    }

    suspend fun stopLaunchConfiguration(id: String) {
        link.call(LinkCall.StopLaunch(id))
    }
}
