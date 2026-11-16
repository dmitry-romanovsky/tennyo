package app.tennyo.host.engine.panel

import app.tennyo.contract.Capability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the host's panel shows, in the one shape both editors render.
 *
 * IntelliJ draws it in Compose and VS Code lists it in a quick pick popup, so it holds no platform types. Keeping
 * it here is what stops the two panels drifting: a state either editor can reach is a state both must handle, and
 * the compiler says so.
 *
 * The six cases are the design's 2b-1 … 2b-6.
 */
@Serializable
sealed interface HostPanelState {
    /** Which devices may reach this computer; the panel's one standing question. */
    val visibility: HostVisibility

    /** Remembered devices, for the row that leads to managing them. */
    val knownDeviceCount: Int

    /** Nothing is running. [reason] says why, so a host that cannot listen at all says so plainly. */
    @Serializable
    @SerialName("no_session")
    data class NoSession(
        override val visibility: HostVisibility = HostVisibility.KNOWN_DEVICES,
        override val knownDeviceCount: Int = 0,
        val reason: NoSessionReason = NoSessionReason.NOT_STARTED,
    ) : HostPanelState

    /** A pairing window is open: this computer is visible under [hostName] until [expiresAtEpochMillis]. */
    @Serializable
    @SerialName("waiting")
    data class Waiting(
        override val visibility: HostVisibility,
        override val knownDeviceCount: Int,
        val hostName: String,
        /** Ungrouped; the panel renders it through `HostShortId.display`. */
        val hostShortId: String,
        val expiresAtEpochMillis: Long,
    ) : HostPanelState

    /** A device is asking to connect and the user has not answered yet. */
    @Serializable
    @SerialName("request")
    data class Request(
        override val visibility: HostVisibility,
        override val knownDeviceCount: Int,
        val device: HostPanelDevice,
        val expiresAtEpochMillis: Long,
    ) : HostPanelState

    /** The words are on screen and the phone is tapping them. */
    @Serializable
    @SerialName("words")
    data class Words(
        override val visibility: HostVisibility,
        override val knownDeviceCount: Int,
        val words: List<String>,
        /** Read from the verifier's configured maximum, never hardcoded. */
        val attemptsLeft: Int,
        val expiresAtEpochMillis: Long,
    ) : HostPanelState

    /** The last attempt was wrong. The words do not change; only the budget does. */
    @Serializable
    @SerialName("wrong_words")
    data class WrongWords(
        override val visibility: HostVisibility,
        override val knownDeviceCount: Int,
        val words: List<String>,
        val attemptsLeft: Int,
        val expiresAtEpochMillis: Long,
    ) : HostPanelState

    /** A device is connected and the session is live. */
    @Serializable
    @SerialName("connected")
    data class Connected(
        override val visibility: HostVisibility,
        override val knownDeviceCount: Int,
        val device: HostPanelDevice,
        val capabilities: Set<Capability>,
    ) : HostPanelState
}

/** The device on the request and connected cards. */
@Serializable
data class HostPanelDevice(
    val name: String,
    val platform: String,
    /** Ungrouped, as [HostPanelState.Waiting.hostShortId] is. */
    val shortId: String,
    /** Whether this host has never seen this device before. */
    val isNew: Boolean = true,
)

/**
 * Who may reach this computer — the one question the panel asks, with three answers (design 2c).
 *
 * This replaces the popup's discovery boolean: [NO_ONE] is discovery off with no window, [KNOWN_DEVICES] is
 * discovery for remembered pairs, and [ALL] is an open pairing window.
 */
@Serializable
enum class HostVisibility(val wireName: String) {
    @SerialName("no_one")
    NO_ONE("no_one"),

    @SerialName("known_devices")
    KNOWN_DEVICES("known_devices"),

    @SerialName("all")
    ALL("all"),
}

@Serializable
enum class NoSessionReason {
    /** Nothing has been started; the panel offers to open pairing mode. */
    @SerialName("not_started")
    NOT_STARTED,

    /** This editor has no listener yet, so it cannot host a session at all. */
    @SerialName("listener_unavailable")
    LISTENER_UNAVAILABLE,
}
