package app.tennyo.host.engine.rpc

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.session.HostSession

internal fun HostSession.launchOutput(call: LinkCall.LaunchOutput): LinkResult =
    LinkResult.LaunchOutput(
        launch.launchOutputSnapshot(call.tabId)
            ?: throw CallFault(LinkFaultCode.TARGET_MISSING, "Launch tab is no longer available."),
    )
