package app.tennyo.host.vscode.panel

import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.panel.HostPanelDevice
import app.tennyo.host.engine.panel.HostPanelState
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.engine.panel.NoSessionReason
import app.tennyo.host.engine.panel.RemoteAccessPanel
import app.tennyo.host.engine.transport.OverlayNetwork

/** What one action of the panel does; the panel turns a click straight into a call on the model. */
internal sealed interface PanelChoice {
    /** The argument the `tennyo.act` command link carries; [panelChoice] reads it back. */
    val wireName: String

    data class Visibility(val visibility: HostVisibility) : PanelChoice {
        override val wireName: String get() = "visibility:${visibility.name}"
    }

    data object ExtendPairingWindow : PanelChoice {
        override val wireName = "extend"
    }

    data object AcceptRequest : PanelChoice {
        override val wireName = "accept"
    }

    data object RejectRequest : PanelChoice {
        override val wireName = "reject"
    }

    data object EndSession : PanelChoice {
        override val wireName = "end"
    }

    /** "Pairing mode" in a window with no folder: a session needs one, so the folder picker comes first. */
    data object OpenFolderToPair : PanelChoice {
        override val wireName = "openFolder"
    }

    /** Lists the remembered phones, each with a way to forget it. */
    data object KnownDevices : PanelChoice {
        override val wireName = "devices"
    }

    data class RemoteAccess(val enabled: Boolean) : PanelChoice {
        override val wireName: String get() = "remote:${if (enabled) "on" else "off"}"
    }

    /** Opens Tailscale's download page, for a computer with no private network to reach it over. */
    data object GetTailscale : PanelChoice {
        override val wireName = "tailscale"
    }
}

/** The choice a `tennyo.act` argument names; `null` for anything else, which the command ignores. */
internal fun panelChoice(wireName: String?): PanelChoice? = when {
    wireName == null -> null
    wireName.startsWith("visibility:") ->
        HostVisibility.entries.firstOrNull { it.name == wireName.removePrefix("visibility:") }
            ?.let(PanelChoice::Visibility)
    wireName == PanelChoice.RemoteAccess(true).wireName -> PanelChoice.RemoteAccess(true)
    wireName == PanelChoice.RemoteAccess(false).wireName -> PanelChoice.RemoteAccess(false)
    else -> listOf(
        PanelChoice.ExtendPairingWindow,
        PanelChoice.AcceptRequest,
        PanelChoice.RejectRequest,
        PanelChoice.EndSession,
        PanelChoice.OpenFolderToPair,
        PanelChoice.KnownDevices,
        PanelChoice.GetTailscale,
    ).firstOrNull { it.wireName == wireName }
}

internal data class PanelButton(val title: String, val choice: PanelChoice)

/**
 * The panel as every surface shows it — the status item, its hover card, the pick list a click opens: the state's
 * codicon and name, label/value rows, a note, then the actions, each with its own codicon.
 */
internal data class PanelCard(
    val icon: String,
    val status: String,
    val rows: List<Pair<String, String>>,
    val note: String? = null,
    val actions: List<PanelButton>,
)

/** A state that cannot wait for a hover: a request to answer, or words to tap. Shown as a notification. */
internal data class PanelAlert(
    val message: String,
    val buttons: List<PanelButton>,
    val warning: Boolean,
)

/**
 * Renders [state] as the card; [hasFolder] tells a window with no folder from one that cannot listen. A card that is
 * not waiting on an answer also shows [remoteAccess] and offers the remembered phones, when there are any.
 */
internal fun panelCard(
    state: HostPanelState,
    remoteAccess: RemoteAccessPanel,
    hasFolder: Boolean,
    nowEpochMillis: Long,
): PanelCard {
    val card = stateCard(state, hasFolder, nowEpochMillis)
    val answering = state is HostPanelState.Request || state is HostPanelState.Words ||
        state is HostPanelState.WrongWords
    if (answering) return card
    val withRemoteAccess = card.copy(
        rows = card.rows + ("Remote access" to remoteAccessDetail(remoteAccess)),
        actions = card.actions + remoteAccessActions(remoteAccess),
    )
    if (state.knownDeviceCount == 0) return withRemoteAccess
    return withRemoteAccess.copy(
        actions = withRemoteAccess.actions + PanelButton("Manage known devices", PanelChoice.KnownDevices),
    )
}

/**
 * The remote access row: off, the private-network addresses a phone away from home reaches this computer at, or what
 * to do when there are none. The firewall sentence is for the common Windows case where the editor's first-run rule
 * covers the home network but not the VPN adapter.
 */
private fun remoteAccessDetail(remoteAccess: RemoteAccessPanel): String = when {
    !remoteAccess.enabled -> "Off"
    remoteAccess.overlay.isEmpty() ->
        "On, but no private network was found. Install Tailscale on this computer and your phone. If the phone " +
            "still cannot connect, allow this editor through Windows Firewall on private networks."
    else -> remoteAccess.overlay.joinToString { "${it.network.displayName} ${it.address}:${remoteAccess.port}" }
}

private fun remoteAccessActions(remoteAccess: RemoteAccessPanel): List<PanelButton> = buildList {
    if (remoteAccess.available || remoteAccess.enabled) {
        add(
            PanelButton(
                if (remoteAccess.enabled) "Turn off remote access" else "Turn on remote access",
                PanelChoice.RemoteAccess(!remoteAccess.enabled),
            ),
        )
    }
    if (remoteAccess.enabled && remoteAccess.overlay.isEmpty()) {
        add(PanelButton("Get Tailscale", PanelChoice.GetTailscale))
    }
}

private val OverlayNetwork.displayName: String
    get() = when (this) {
        OverlayNetwork.TAILNET_RANGE -> "Tailscale"
        OverlayNetwork.ZEROTIER -> "ZeroTier"
        OverlayNetwork.WIREGUARD -> "WireGuard"
    }

private fun stateCard(state: HostPanelState, hasFolder: Boolean, nowEpochMillis: Long): PanelCard {
    val known = "Paired phones" to state.knownDeviceCount.toString()
    return when (state) {
        is HostPanelState.NoSession -> noSessionCard(state, hasFolder, known)

        is HostPanelState.Waiting -> PanelCard(
            icon = state.icon,
            status = "Pairing mode",
            rows = listOf(
                "Pick on your phone" to "${state.hostName} (${grouped(state.hostShortId)})",
                "Visible for" to remaining(state.expiresAtEpochMillis, nowEpochMillis),
                known,
            ),
            actions = listOf(
                PanelButton("Keep open 2 more minutes", PanelChoice.ExtendPairingWindow),
                visibilityButton(HostVisibility.KNOWN_DEVICES),
                visibilityButton(HostVisibility.NO_ONE),
            ),
        )

        is HostPanelState.Request -> PanelCard(
            icon = state.icon,
            status = "Pairing request",
            rows = listOf("Wants to connect" to device(state.device)),
            note = "Accept only if you started this from your phone.",
            actions = listOf(
                PanelButton("Accept", PanelChoice.AcceptRequest),
                PanelButton("Reject", PanelChoice.RejectRequest),
            ),
        )

        is HostPanelState.Words -> wordsCard(state.icon, state.words, state.attemptsLeft, wrong = false)

        is HostPanelState.WrongWords -> wordsCard(state.icon, state.words, state.attemptsLeft, wrong = true)

        is HostPanelState.Connected -> PanelCard(
            icon = state.icon,
            status = "Connected",
            rows = listOf(
                "Device" to state.device.name.ifBlank { "Your phone" },
                "Shared" to state.capabilities.joinToString { capability ->
                    capability.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
                },
            ),
            actions = listOf(PanelButton("End session", PanelChoice.EndSession)),
        )
    }
}

/** The notification a state raises on its own, or `null` when the hover card is enough. */
internal fun panelAlert(state: HostPanelState): PanelAlert? = when (state) {
    is HostPanelState.Request -> PanelAlert(
        message = "${device(state.device)} wants to connect to this editor. Accept only if you started this.",
        buttons = listOf(
            PanelButton("Accept", PanelChoice.AcceptRequest),
            PanelButton("Reject", PanelChoice.RejectRequest),
        ),
        warning = true,
    )

    is HostPanelState.Words -> PanelAlert(
        message = "Tap these words on your phone, in this order: ${words(state.words)}.",
        buttons = listOf(PanelButton("Cancel pairing", PanelChoice.EndSession)),
        warning = false,
    )

    is HostPanelState.WrongWords -> PanelAlert(
        message = "That was not the right order. Tap the same words again: ${words(state.words)}. " +
            "${attemptsLeft(state.attemptsLeft)} left.",
        buttons = listOf(PanelButton("Cancel pairing", PanelChoice.EndSession)),
        warning = true,
    )

    else -> null
}

/**
 * The card as trusted Markdown: a two-column table (right-aligned values) under the title, the note, then the actions
 * as `tennyo.act` command links. Values are escaped, since device names come from the phone.
 */
internal fun PanelCard.markdown(): String = buildString {
    append("| $icon **Tennyo** | ${escape(status)} |\n|:--|--:|\n")
    rows.forEach { (label, value) -> append("| ${escape(label)} | ${escape(value)} |\n") }
    note?.let { append("\n${escape(it)}\n") }
    if (actions.isNotEmpty()) {
        append("\n---\n\n")
        append(actions.joinToString(" &nbsp;·&nbsp; ") { "[${it.icon} ${escape(it.title)}](${commandLink(it)})" })
    }
}

private fun wordsCard(icon: String, words: List<String>, attemptsLeft: Int, wrong: Boolean) = PanelCard(
    icon = icon,
    status = if (wrong) "Wrong order" else "Confirm on phone",
    rows = listOf(
        "Tap on your phone" to words(words),
        "Attempts left" to attemptsLeft.toString(),
    ),
    actions = listOf(PanelButton("Cancel pairing", PanelChoice.EndSession)),
)

private fun noSessionCard(
    state: HostPanelState.NoSession,
    hasFolder: Boolean,
    known: Pair<String, String>,
): PanelCard {
    val others = HostVisibility.entries.filter { it != state.visibility }
    return when {
        state.reason == NoSessionReason.NOT_STARTED -> PanelCard(
            icon = state.icon,
            status = state.visibility.modeName,
            rows = listOf(known),
            note = state.visibility.detail,
            actions = others.map(::visibilityButton),
        )

        !hasFolder -> PanelCard(
            icon = state.icon,
            status = state.visibility.modeName,
            rows = listOf(known),
            note = "Open a folder to pair a phone or be found by one.",
            actions = others.map { visibility ->
                if (visibility == HostVisibility.ALL) {
                    PanelButton("Open a folder to pair", PanelChoice.OpenFolderToPair)
                } else {
                    visibilityButton(visibility)
                }
            },
        )

        else -> PanelCard(
            icon = "$(warning)",
            status = "Unreachable",
            rows = listOf(known),
            note = "Tennyo cannot listen on this computer's local network, so no phone can reach it. " +
                "Check that it is connected to a private network.",
            actions = others.filter { it != HostVisibility.ALL }.map(::visibilityButton),
        )
    }
}

private fun commandLink(button: PanelButton): String =
    "command:${PANEL_COMMAND}?" + encodeURIComponent(JSON.stringify(arrayOf(button.choice.wireName)))

/** Markdown and table syntax a phone-supplied name could carry. */
private fun escape(text: String): String = text.replace(MARKDOWN_SPECIAL) { "\\${it.value}" }

private val MARKDOWN_SPECIAL = Regex("""[\\`*_{}\[\]()#+\-!|<>]""")

private fun visibilityButton(visibility: HostVisibility) =
    PanelButton(visibility.buttonTitle, PanelChoice.Visibility(visibility))

/** The mode as the card's status names it. */
internal val HostVisibility.modeName: String
    get() = when (this) {
        HostVisibility.ALL -> "Pairing mode"
        HostVisibility.KNOWN_DEVICES -> "Known devices"
        HostVisibility.NO_ONE -> "Off"
    }

/** The button that switches to the mode, worded as what it does. */
private val HostVisibility.buttonTitle: String
    get() = when (this) {
        HostVisibility.ALL -> "Open pairing mode"
        HostVisibility.KNOWN_DEVICES -> "Allow only known devices"
        HostVisibility.NO_ONE -> "Turn off"
    }

private val HostVisibility.detail: String
    get() = when (this) {
        HostVisibility.ALL -> "Any phone on this network can ask to pair."
        HostVisibility.KNOWN_DEVICES -> "Phones you paired before can reconnect at any time."
        HostVisibility.NO_ONE -> "No phone can reach this computer."
    }

private fun device(device: HostPanelDevice): String =
    buildString {
        append(device.name.ifBlank { "A phone" })
        val details = listOf(device.platform, grouped(device.shortId)).filter { it.isNotBlank() }
        if (details.isNotEmpty()) append(" (${details.joinToString(", ")})")
    }

private fun attemptsLeft(count: Int): String = if (count == 1) "1 attempt" else "$count attempts"

private fun words(words: List<String>): String =
    words.mapIndexed { index, word -> "${index + 1} $word" }.joinToString(" · ")

/** One remembered phone's second line: when it last connected and when this computer forgets it. */
internal fun knownDeviceDetail(device: RememberedPairSummary, nowEpochMillis: Long): String {
    val lastUsed = device.lastUsedAtEpochMillis.takeIf { it > 0 }?.let { "used ${ago(it, nowEpochMillis)}" }
    val hoursLeft = ((device.expiresAtEpochMillis - nowEpochMillis) / MILLIS_PER_HOUR).coerceAtLeast(0)
    val expiry = if (hoursLeft >= 2 * HOURS_PER_DAY) {
        "forgotten in ${hoursLeft / HOURS_PER_DAY} d"
    } else {
        "forgotten in $hoursLeft h"
    }
    return listOfNotNull(lastUsed, expiry).joinToString(" · ")
}

private fun ago(epochMillis: Long, nowEpochMillis: Long): String {
    val minutes = ((nowEpochMillis - epochMillis) / MILLIS_PER_MINUTE).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

/** Whole minutes, rounded up: the card changes once a minute, not every second, so an open hover stays still. */
private fun remaining(expiresAtEpochMillis: Long, nowEpochMillis: Long): String {
    val millis = (expiresAtEpochMillis - nowEpochMillis).coerceAtLeast(0)
    val minutes = (millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE
    return if (minutes <= 1) "under a minute" else "$minutes min"
}

/** The short id as the panels show it: four-character groups. */
private fun grouped(shortId: String): String = shortId.chunked(ID_GROUP).joinToString(" ")

/** The state's codicon; the action that leads to a state shows the same one. */
internal val HostPanelState.icon: String
    get() = when (this) {
        is HostPanelState.Connected -> "$(pass-filled)"
        is HostPanelState.Waiting -> HostVisibility.ALL.icon
        is HostPanelState.Request -> "$(bell-dot)"
        is HostPanelState.Words, is HostPanelState.WrongWords -> "$(key)"
        is HostPanelState.NoSession -> visibility.icon
    }

internal val PanelButton.icon: String
    get() = when (choice) {
        is PanelChoice.Visibility -> choice.visibility.icon
        PanelChoice.ExtendPairingWindow -> "$(clock)"
        PanelChoice.AcceptRequest -> "$(check)"
        PanelChoice.RejectRequest -> "$(close)"
        PanelChoice.EndSession -> "$(debug-disconnect)"
        PanelChoice.OpenFolderToPair -> "$(folder-opened)"
        PanelChoice.KnownDevices -> "$(list-unordered)"
        is PanelChoice.RemoteAccess -> "$(globe)"
        PanelChoice.GetTailscale -> "$(link-external)"
    }

private val HostVisibility.icon: String
    get() = when (this) {
        HostVisibility.ALL -> "$(radio-tower)"
        HostVisibility.KNOWN_DEVICES -> "$(device-mobile)"
        HostVisibility.NO_ONE -> "$(circle-slash)"
    }

private external fun encodeURIComponent(text: String): String

/** The command every card link runs, with the [PanelChoice.wireName] as its one argument. */
internal const val PANEL_COMMAND = "tennyo.act"

/** The status item's click and the command palette's "Tennyo: Manage Connection". */
const val SHOW_PANEL_COMMAND = "tennyo.showCompanionPanel"

private const val ID_GROUP = 4
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val HOURS_PER_DAY = 24L
