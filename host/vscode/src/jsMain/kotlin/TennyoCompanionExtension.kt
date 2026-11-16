@file:OptIn(ExperimentalJsExport::class)

// No package: VS Code calls `activate` and `deactivate` on the module itself, and Kotlin/JS exports a declaration at
// the top level only when it is in the root package.

import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.engine.platform.EngineSelfCheck
import app.tennyo.host.vscode.VsCodeLogWriter
import app.tennyo.host.vscode.api.ExtensionContext
import app.tennyo.host.vscode.api.LogOutputChannelOptions
import app.tennyo.host.vscode.api.StatusBarAlignment
import app.tennyo.host.vscode.api.VsCodeCommands
import app.tennyo.host.vscode.api.VsCodeEnv
import app.tennyo.host.vscode.api.VsCodeWindow
import app.tennyo.host.vscode.api.VsCodeWorkspace
import app.tennyo.host.vscode.api.version
import app.tennyo.host.vscode.panel.AgentsWindow
import app.tennyo.host.vscode.panel.CompanionPanel
import app.tennyo.host.vscode.panel.PANEL_COMMAND
import app.tennyo.host.vscode.panel.SHOW_PANEL_COMMAND
import app.tennyo.host.vscode.panel.panelChoice
import app.tennyo.host.vscode.session.VsCodeCompanionHost
import co.touchlab.kermit.Logger

private const val OUTPUT_CHANNEL_NAME = "Tennyo Companion"
private const val OPEN_FOLDER_COMMAND = "vscode.openFolder"
private const val STATUS_BAR_PRIORITY = 100

/** Set when "Pairing mode" had to open a folder first; the window that opens with it starts pairing. */
private const val PAIR_WHEN_FOLDER_OPENS = "tennyo.pairWhenFolderOpens"

private var companion: VsCodeCompanionHost? = null
private var panel: CompanionPanel? = null

/** Called by the editor once startup has finished; checks that the shared core runs here and starts the host. */
@JsExport
fun activate(context: ExtensionContext) {
    val options = js("{}").unsafeCast<LogOutputChannelOptions>().apply { log = true }
    val channel = VsCodeWindow.createOutputChannel(OUTPUT_CHANNEL_NAME, options)
    context.subscriptions.asDynamic().push(channel)
    Logger.setLogWriters(VsCodeLogWriter(channel))

    val host = VsCodeCompanionHost(
        hostVersion = "${VsCodeEnv.appName} $version",
        globalState = context.globalState,
        workspaceState = context.workspaceState,
        userDataPath = userDataPath(context),
        onStateChanged = ::redraw,
    )
    companion = host
    val item = VsCodeWindow.createStatusBarItem(StatusBarAlignment.Right, STATUS_BAR_PRIORITY)
    context.subscriptions.asDynamic().push(item)
    panel = CompanionPanel(
        model = host.panelModel,
        item = item,
        hasFolder = { VsCodeWorkspace.workspaceFolders?.isNotEmpty() == true },
        openFolderToPair = {
            context.globalState.update(PAIR_WHEN_FOLDER_OPENS, true)
            VsCodeCommands.executeCommand(OPEN_FOLDER_COMMAND)
        },
    )

    context.subscriptions.asDynamic().push(
        VsCodeCommands.registerCommand(PANEL_COMMAND) { argument ->
            panelChoice(argument as? String)?.let { panel?.act(it) }
        },
    )
    context.subscriptions.asDynamic().push(VsCodeCommands.registerCommand(SHOW_PANEL_COMMAND) { panel?.show() })
    val agentsWindow = AgentsWindow(context.extension.id as String, context.globalState)
    context.subscriptions.asDynamic().push(
        VsCodeCommands.registerCommand(AgentsWindow.COMMAND) { agentsWindow.enable() },
    )
    agentsWindow.offerOnce()
    panel?.refresh()
    item.show()

    when (val failure = EngineSelfCheck.run()) {
        null -> channel.info("Tennyo Companion core ready")
        else -> channel.error("Tennyo Companion core self-check failed: $failure")
    }

    val start = {
        // The listener binds off the activation path: the editor must not wait on a socket to finish starting up.
        host.start { listening ->
            channel.info(if (listening) "Tennyo Companion is listening" else "Tennyo Companion is idle")
            if (listening && context.globalState.get(PAIR_WHEN_FOLDER_OPENS) == true) {
                context.globalState.update(PAIR_WHEN_FOLDER_OPENS, false)
                host.panelModel.setVisibility(HostVisibility.ALL)
            }
            redraw()
        }
    }
    // A folder added to this window, rather than opened by reloading it, builds the host here.
    context.subscriptions.asDynamic().push(VsCodeWorkspace.onDidChangeWorkspaceFolders { start() })
    start()
}

private fun redraw() {
    panel?.refresh()
}

@JsExport
fun deactivate() {
    panel?.dispose()
    panel = null
    companion?.stop()
    companion = null
}

/** The editor's user data directory: `globalStorageUri` is `<userData>/User/globalStorage/<extension id>`. */
private fun userDataPath(context: ExtensionContext): String =
    js("require('node:path')").resolve(context.globalStorageUri.fsPath, "..", "..", "..") as String
