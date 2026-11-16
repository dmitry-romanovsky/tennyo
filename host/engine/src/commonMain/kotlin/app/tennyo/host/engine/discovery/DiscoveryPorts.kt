package app.tennyo.host.engine.discovery

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.ProjectChoice
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.SessionHost

/** One open project as discovery sees it. */
interface DiscoveryProject {
    val projectId: String
    val host: SessionHost

    fun isDiscoveryEnabled(): Boolean

    /** Whether this project may answer discovery now: it owns discovery and no session is running anywhere. */
    fun isDiscoveryAvailable(): Boolean

    fun discoveryBlockedMessage(): String?

    fun discoveryProjectChoices(): List<ProjectChoice>

    fun discoveryDescriptor(): KnownHostDescriptor

    fun discoveryEndpoints(): HostEndpoints

    /** Counts a failed verification against the lockout; `true` when it blocks discovery. */
    fun recordDiscoverySecurityFailure(message: String, attackerSource: String?): Boolean
}

/** The IDE's open projects, for the discovery routes. */
interface ProjectDirectory {
    /** The project answering discovery for [companionId], or `null` when that id is not current or discovery is off. */
    fun discoveryOwner(companionId: String): DiscoveryProject?

    /**
     * The project whose pairing window is open, if any. An offer carries no companion id — a phone that has never
     * spoken to this host has nothing to name it by — so the directory answers with whichever project is open.
     */
    fun pairingWindowOwner(): DiscoveryProject?

    fun project(projectId: String): DiscoveryProject?

    /** The user-facing text for [message] in the IDE's language. */
    fun message(message: DiscoveryMessage): String
}

enum class DiscoveryMessage {
    CONNECT_DISABLED,
    PAIRING_REQUIRED,
    PROJECT_NOT_FOUND,
    SESSION_UNAVAILABLE,
}

/** Announces the companion on the local network. */
interface DiscoveryAdvertiser {
    /**
     * [pairingWindowOpen] rides along as a TXT hint so a phone can probe the hosts most likely to answer first.
     * Nothing reads it as truth: the offer itself is the answer, and the short id it carries is what is checked.
     */
    fun advertise(companionId: String, endpoint: String, pairingWindowOpen: Boolean = false)

    fun unadvertise(companionId: String)
}
