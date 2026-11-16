package app.tennyo.host.intellij

import app.tennyo.host.engine.discovery.DiscoveryMessage
import app.tennyo.host.engine.discovery.DiscoveryProject
import app.tennyo.host.engine.discovery.ProjectDirectory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service

/** The open projects' session services, as registered with [HostGlobalSettings]. */
internal object IdeProjectDirectory : ProjectDirectory {
    override fun discoveryOwner(companionId: String): DiscoveryProject? = settings().ownerService(companionId)

    override fun pairingWindowOwner(): DiscoveryProject? = settings().pairingWindowService()

    override fun project(projectId: String): DiscoveryProject? = settings().projectService(projectId)

    override fun message(message: DiscoveryMessage): String =
        HostBundle.message(
            when (message) {
                DiscoveryMessage.CONNECT_DISABLED -> "discovery.connect.unavailable.disabled"
                DiscoveryMessage.PAIRING_REQUIRED -> "discovery.connect.pairing.required"
                DiscoveryMessage.PROJECT_NOT_FOUND -> "discovery.connect.project.not.found"
                DiscoveryMessage.SESSION_UNAVAILABLE -> "discovery.connect.session.unavailable"
            }
        )

    private fun settings(): HostGlobalSettings =
        ApplicationManager.getApplication().service<HostGlobalSettings>()
}
