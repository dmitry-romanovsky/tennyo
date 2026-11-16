package app.tennyo.mobile.link

import kotlinx.serialization.Serializable

/**
 * The app's handle on one host session: where to reach it, what authenticates it, and which certificate it must
 * present. It arrives sealed — from a pairing confirmation or a discovery connect — and is never rendered as a
 * link, so the token exists only inside the app and its secure storage.
 */
@Serializable
data class LinkTicket(
    val endpoint: String,
    val sessionId: String,
    val token: String,
    val fingerprint: String,
    val projectName: String,
    val projectPath: String,
    val alternateEndpoints: List<String> = emptyList(),
    val companionId: String? = null,
    val hostVersion: String? = null,
    /**
     * Whether [token] is an open pairing window's credential rather than a session token. It reaches the claim and
     * confirm steps only, so a connection under it starts at the claim; the confirmation hands back the real ticket.
     */
    val isPairingWindow: Boolean = false,
) {

    /** The addresses worth trying, best first; a loopback address belongs to the host, never to this phone. */
    fun endpointCandidates(): List<String> =
        (listOf(endpoint) + alternateEndpoints)
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && it.isSecureHttpEndpoint() && !it.isLoopbackEndpoint() }
            .distinct()

    fun withEndpoint(endpoint: String): LinkTicket =
        if (endpoint.trimEnd('/') == this.endpoint.trimEnd('/')) {
            this
        } else {
            copy(endpoint = endpoint.trimEnd('/'))
        }
}

private fun String.isSecureHttpEndpoint(): Boolean =
    startsWith("https://", ignoreCase = true)

private fun String.isLoopbackEndpoint(): Boolean {
    val authority = substringAfter("://", this)
        .substringBefore('/')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
        .trim()
        .lowercase()
    return host == "localhost" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.")
}
