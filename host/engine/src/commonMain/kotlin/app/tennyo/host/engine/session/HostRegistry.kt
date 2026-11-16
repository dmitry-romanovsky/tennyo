package app.tennyo.host.engine.session

import app.tennyo.host.engine.platform.HostConcurrentSet

/**
 * Which secret authenticated a request. A [PairingToken] is scoped to an open pairing window and may reach only
 * the claim and confirm steps; `hello`, discovery connect and the channel refuse it. The session token itself
 * never leaves the host until the user approves the device, and then only sealed under the pairing key.
 */
sealed interface SessionCredential {
    val token: String

    data class SessionToken(override val token: String) : SessionCredential

    data class PairingToken(override val token: String) : SessionCredential
}

data class SessionMatch(
    val host: SessionHost,
    val session: HostSession,
    val credential: SessionCredential,
)

/**
 * Every running [SessionHost] in the process, one per open project. The shared listener finds the session a
 * channel or request belongs to here; a host is registered for as long as its project is open.
 */
object HostRegistry {
    private val hosts = HostConcurrentSet<SessionHost>()

    fun register(host: SessionHost) {
        hosts.add(host)
    }

    fun unregister(host: SessionHost) {
        hosts.remove(host)
    }

    fun find(sessionId: String?, token: String?): SessionMatch? =
        hosts.toList().firstNotNullOfOrNull { host -> host.match(sessionId, token) }
}
