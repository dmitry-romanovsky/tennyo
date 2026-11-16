package app.tennyo.host.engine.session

import app.tennyo.contract.Capability
import kotlinx.serialization.Serializable

/** Everything the phone needs to reach and authenticate to one companion session; it reaches the phone encrypted. */
@Serializable
data class SessionTicket(
    val endpoint: String,
    val sessionId: String,
    val token: String,
    val fingerprint: String,
    val projectName: String,
    val projectPath: String,
    val companionId: String = "",
    val hostVersion: String = "",
    val capabilities: Set<Capability>,
    val alternateEndpoints: List<String> = emptyList(),
)
