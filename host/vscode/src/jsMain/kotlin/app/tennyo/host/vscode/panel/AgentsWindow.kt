package app.tennyo.host.vscode.panel

import app.tennyo.host.vscode.api.Memento
import app.tennyo.host.vscode.api.MessageItem
import app.tennyo.host.vscode.api.VsCodeEnv
import app.tennyo.host.vscode.api.VsCodeWindow
import app.tennyo.host.vscode.api.VsCodeWorkspace

/**
 * VS Code's Agents window runs no extension that executes code unless the user's `extensions.supportAgentsWindow`
 * setting lets it in; the manifest's own way in is a proposed API the Marketplace refuses. This puts Tennyo in that
 * setting, so the window gets its status item, panel and pairing notifications and its phone can reach it. The
 * setting is read when the Agents window opens.
 */
internal class AgentsWindow(private val extensionId: String, private val globalState: Memento) {
    private val isEnabled: Boolean get() = allowed()[extensionId] == true

    /** Lets Tennyo into the Agents window, saying when it takes effect. */
    fun enable() {
        if (isEnabled) {
            VsCodeWindow.showInformationMessage(ALREADY_ENABLED)
            return
        }
        val updated = js("{}")
        js("Object.assign")(updated, allowed())
        updated[extensionId] = true
        VsCodeWorkspace.getConfiguration("extensions").update(SETTING, updated, global = true).then(
            { VsCodeWindow.showInformationMessage(ENABLED) },
            { failure -> VsCodeWindow.showWarningMessage("Tennyo could not change $SETTING: ${failure?.message}") },
        )
    }

    /** Asks once, in VS Code itself, whether Tennyo should run in the Agents window too; its forks have none. */
    fun offerOnce() {
        if (isEnabled || globalState.get(OFFERED) == true || !VsCodeEnv.appName.startsWith(VS_CODE)) return
        globalState.update(OFFERED, true)
        val accept = js("{}").unsafeCast<MessageItem>().apply { title = "Show in Agents window" }
        val decline = js("{}").unsafeCast<MessageItem>().apply { title = "Not now" }
        VsCodeWindow.showInformationMessage(OFFER, accept, decline).then({ picked ->
            if (picked == accept) enable()
        }, {})
    }

    private fun allowed(): dynamic = VsCodeWorkspace.getConfiguration("extensions").get(SETTING) ?: js("{}")

    companion object {
        const val COMMAND = "tennyo.showInAgentsWindow"
        private const val SETTING = "supportAgentsWindow"
        private const val OFFERED = "tennyo.agentsWindowOffered"
        private const val VS_CODE = "Visual Studio Code"
        private const val OFFER =
            "Also run Tennyo in VS Code's Agents window? Your phone can then pair with that window too."
        private const val ENABLED = "Tennyo will run in the Agents window the next time it opens."
        private const val ALREADY_ENABLED = "Tennyo already runs in the Agents window."
    }
}
