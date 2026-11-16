package app.tennyo.mobile.feature.launch

import app.tennyo.mobile.feature.launch.generated.resources.Res
import app.tennyo.mobile.feature.launch.generated.resources.load_launch_configurations_failed
import app.tennyo.mobile.feature.launch.generated.resources.start_launch_failed
import app.tennyo.mobile.feature.launch.generated.resources.stop_launch_failed
import org.jetbrains.compose.resources.StringResource

/** A launch action that can fail without ending the link session. */
internal enum class LaunchAction(val failure: StringResource) {
    StartLaunch(Res.string.start_launch_failed),
    StopLaunch(Res.string.stop_launch_failed),
    LoadLaunchConfigurations(Res.string.load_launch_configurations_failed),
}
