package app.tennyo.host.vscode.panel

import app.tennyo.host.engine.panel.HostPanelModel
import app.tennyo.host.engine.panel.HostPanelState
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.vscode.api.MarkdownString
import app.tennyo.host.vscode.api.MessageItem
import app.tennyo.host.vscode.api.QuickPick
import app.tennyo.host.vscode.api.StatusBarItem
import app.tennyo.host.vscode.api.ThemeColor
import app.tennyo.host.vscode.api.ThemeIcon
import app.tennyo.host.vscode.api.Uri
import app.tennyo.host.vscode.api.VsCodeEnv
import app.tennyo.host.vscode.api.VsCodeWindow

/**
 * The companion panel in this editor: the status item's hover card, the same card as a pick list when the item is
 * clicked, and a notification only for what cannot wait — a pairing request and the words to tap. Both the hover and
 * the list redraw while open. It holds no state beyond what it shows; the model is the only source.
 *
 * The status item's click opens the list, while its hover remains available on pointer hover. VS Code reserves
 * click-to-open behavior for its own tooltip actions.
 */
class CompanionPanel(
    private val model: HostPanelModel,
    private val item: StatusBarItem,
    private val hasFolder: () -> Boolean,
    private val openFolderToPair: () -> Unit,
) {
    private var alerted: HostPanelState? = null
    private var ticking = false
    private var pick: QuickPick? = null

    // What each surface shows now. The editor redraws an open hover or list on every assignment, so a surface is
    // assigned only when its content changes; a per-second assignment is what made them flicker.
    private var shownText: String? = null
    private var shownTooltip: String? = null
    private var shownPick: List<String>? = null
    private var shownAlarm: Boolean? = null
    private var devices: QuickPick? = null

    init {
        item.command = SHOW_PANEL_COMMAND
    }

    /** Redraws the card for the current state, and alerts once when the state starts needing an answer. */
    fun refresh() {
        val state = model.state
        val card = panelCard(state, model.remoteAccess, hasFolder(), hostWallClockMillis())
        val text = "${card.icon} Tennyo"
        if (text != shownText) {
            shownText = text
            item.text = text
            item.accessibilityInformation = accessibility("Tennyo: ${card.status}")
        }
        // A request waits on this computer's user, so the item takes the warning colour until it is answered.
        val alarm = state is HostPanelState.Request
        if (alarm != shownAlarm) {
            shownAlarm = alarm
            item.backgroundColor = if (alarm) ThemeColor(WARNING_BACKGROUND) else undefined
        }
        val markdown = card.markdown()
        if (markdown != shownTooltip) {
            shownTooltip = markdown
            // Only the card's own command runs from its links.
            val trust = js("{}")
            trust.enabledCommands = arrayOf(PANEL_COMMAND)
            item.tooltip = MarkdownString(markdown, true).apply { isTrusted = trust }
        }
        pick?.let { open -> fill(open, card) }
        alertIfNew(state)
        keepCountdownFresh(state)
    }

    /** Runs a card link or the status item's click. */
    internal fun act(choice: PanelChoice) {
        when (choice) {
            is PanelChoice.Visibility -> model.setVisibility(choice.visibility)
            PanelChoice.ExtendPairingWindow -> model.extendPairingWindow()
            PanelChoice.AcceptRequest -> model.acceptRequest()
            PanelChoice.RejectRequest -> model.rejectRequest()
            PanelChoice.EndSession -> model.endSession()
            PanelChoice.OpenFolderToPair -> openFolderToPair()
            PanelChoice.KnownDevices -> showKnownDevices()
            is PanelChoice.RemoteAccess -> model.setRemoteAccess(choice.enabled)
            PanelChoice.GetTailscale -> VsCodeEnv.openExternal(Uri.parse(TAILSCALE_DOWNLOAD_URL))
        }
        refresh()
    }

    /** The status item's click: the card as a pick list, which redraws with the session until it is dismissed. */
    fun show() {
        pick?.let { return it.show() }
        val list = VsCodeWindow.createQuickPick()
        list.matchOnDescription = true
        list.onDidAccept {
            val choice = list.selectedItems.firstOrNull()?.choice?.unsafeCast<PanelChoice>()
            if (choice != null) {
                list.hide()
                act(choice)
            }
        }
        list.onDidHide {
            pick = null
            shownPick = null
            list.dispose()
        }
        pick = list
        fill(list, panelCard(model.state, model.remoteAccess, hasFolder(), hostWallClockMillis()))
        list.show()
    }

    fun dispose() {
        ticking = false
        pick?.hide()
        devices?.hide()
    }

    /** The remembered phones, each with a button that forgets it after the editor's own confirmation dialog. */
    private fun showKnownDevices() {
        devices?.let { return it.show() }
        val list = VsCodeWindow.createQuickPick()
        list.title = "Tennyo · Known devices"
        list.placeholder = "Phones that reconnect without the pairing words"
        list.onDidTriggerItemButton { event -> confirmForget(event.item) }
        // Not `?.let`: on a `dynamic` item that compiles to a JavaScript method call the item lacks.
        list.onDidAccept {
            val picked: dynamic = list.selectedItems.firstOrNull()
            if (picked != null) confirmForget(picked)
        }
        list.onDidHide {
            devices = null
            list.dispose()
        }
        devices = list
        fillDevices(list)
        list.show()
    }

    private fun fillDevices(list: QuickPick) {
        val now = hostWallClockMillis()
        val known = model.knownDevices()
        list.items = if (known.isEmpty()) {
            arrayOf(pickItem("No phone is remembered yet", "Pair one in pairing mode"))
        } else {
            known.map { device ->
                val entry = pickItem("$(device-mobile) ${device.deviceLabel}", knownDeviceDetail(device, now))
                entry.pairId = device.pairId
                entry.deviceLabel = device.deviceLabel
                entry.buttons = arrayOf(forgetButton())
                entry
            }.toTypedArray()
        }
    }

    private fun confirmForget(entry: dynamic) {
        val pairId = entry?.pairId as? String ?: return
        val forget = js("{}").unsafeCast<MessageItem>().apply { title = "Forget" }
        val modal = js("{}")
        modal.modal = true
        modal.detail = "It has to pair again before it can connect."
        val name = entry.deviceLabel as String
        VsCodeWindow.showWarningMessage("Forget $name?", modal, forget)
            .then(
                { picked ->
                    if (picked != null) {
                        model.forgetDevice(pairId)
                        devices?.let(::fillDevices)
                        refresh()
                    }
                },
                { },
            )
    }

    private fun fill(list: QuickPick, card: PanelCard) {
        val title = "Tennyo · ${card.status}"
        val placeholder = card.note ?: "Choose an action"
        val content = listOf(title, placeholder) + card.rows.map { (label, value) -> "$label=$value" } +
            card.actions.map { it.title }
        if (content == shownPick) return
        shownPick = content
        list.title = title
        list.placeholder = placeholder
        val items = mutableListOf<dynamic>()
        card.rows.forEach { (label, value) -> items.add(pickItem(label, value)) }
        items.add(separator("Actions"))
        card.actions.forEach { action -> items.add(pickItem("${action.icon} ${action.title}", null, action.choice)) }
        list.items = items.toTypedArray()
    }

    private fun alertIfNew(state: HostPanelState) {
        val alert = panelAlert(state)
        if (alert == null) {
            alerted = null
            return
        }
        // The same request or the same words are announced once, however often the state is redrawn.
        if (alerted == state) return
        alerted = state
        val items = alert.buttons.map { button ->
            js("{}").unsafeCast<MessageItem>().apply { title = button.title }.also { it.asDynamic().choice = button }
        }.toTypedArray()
        val shown = if (alert.warning) {
            VsCodeWindow.showWarningMessage(alert.message, *items)
        } else {
            VsCodeWindow.showInformationMessage(alert.message, *items)
        }
        shown.then(
            { picked ->
                // A click on an alert the session has moved past acts on nothing.
                val button = picked?.asDynamic()?.choice?.unsafeCast<PanelButton>()
                if (button != null && model.state == state) act(button.choice)
            },
            { },
        )
    }

    // The card shows how long pairing mode stays visible; nothing else changes it while it counts down.
    private fun keepCountdownFresh(state: HostPanelState) {
        if (state !is HostPanelState.Waiting || ticking) return
        ticking = true
        setTimeout({
            ticking = false
            if (model.state is HostPanelState.Waiting) refresh()
        }, COUNTDOWN_REFRESH_MILLIS)
    }

    private companion object {
        const val COUNTDOWN_REFRESH_MILLIS = 1_000
        const val TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download"
    }
}

private external fun setTimeout(callback: () -> Unit, delayMillis: Int): dynamic

private fun pickItem(label: String, description: String?, choice: PanelChoice? = null): dynamic {
    val item = js("{}")
    item.label = label
    if (description != null) item.description = description
    if (choice != null) item.choice = choice
    return item
}

private fun separator(label: String): dynamic {
    val item = js("{}")
    item.label = label
    item.kind = QUICK_PICK_SEPARATOR
    return item
}

private fun forgetButton(): dynamic {
    val button = js("{}")
    button.iconPath = ThemeIcon("trash")
    button.tooltip = "Forget this phone"
    return button
}

private fun accessibility(label: String): dynamic {
    val information = js("{}")
    information.label = label
    information.role = "button"
    return information
}

/** `vscode.QuickPickItemKind.Separator`. */
private const val QUICK_PICK_SEPARATOR = -1

/** The one status bar background a warning may use. */
private const val WARNING_BACKGROUND = "statusBarItem.warningBackground"
