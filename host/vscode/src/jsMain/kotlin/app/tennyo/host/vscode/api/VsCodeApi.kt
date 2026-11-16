@file:JsModule("vscode")
// Externals declare signatures only; their parameters are used by the JavaScript side.
@file:Suppress("UnusedParameter")

package app.tennyo.host.vscode.api

/**
 * The slice of the `vscode` API the extension uses, written by hand: the `kotlin-vscode` wrappers target a newer VS
 * Code API than the `engines.vscode` floor, so binding them would let newer calls compile.
 */
external interface ExtensionContext {
    val subscriptions: Array<Disposable>

    /** Values kept across restarts for this extension, shared by every window. */
    val globalState: Memento

    /** Values kept across restarts for this extension in this folder or workspace only. */
    val workspaceState: Memento

    /** `<userData>/User/globalStorage/<extension id>`: the one path the API gives into the editor's user data. */
    val globalStorageUri: Uri

    /** This extension; its `id` is `publisher.name`. */
    val extension: dynamic
}

external interface Memento {
    fun get(key: String): dynamic

    fun update(key: String, value: Any?): Thenable<Unit>
}

/** A button on a notification; the promise the notification returns resolves to the one that was clicked. */
external interface MessageItem {
    var title: String
}

external interface Disposable {
    fun dispose()
}

/** An output channel with log levels, as `window.createOutputChannel(name, { log: true })` returns it (1.74+). */
external interface LogOutputChannel : Disposable {
    fun trace(message: String)

    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String)

    fun error(message: String)
}

external interface LogOutputChannelOptions {
    var log: Boolean
}

@JsName("window")
external object VsCodeWindow {
    fun createOutputChannel(name: String, options: LogOutputChannelOptions): LogOutputChannel

    fun createStatusBarItem(alignment: Int, priority: Int): StatusBarItem

    /** A pick list at the top of the window; its items can change while it is open. */
    fun createQuickPick(): QuickPick

    /** A notification in the editor's corner; resolves to the clicked item, or `undefined` once dismissed. */
    fun showInformationMessage(message: String, vararg items: MessageItem): Thenable<MessageItem?>

    fun showWarningMessage(message: String, vararg items: MessageItem): Thenable<MessageItem?>

    /** A warning with [options]; `{ modal: true }` asks in a dialog, with the editor's own Cancel. */
    fun showWarningMessage(message: String, options: dynamic, vararg items: MessageItem): Thenable<MessageItem?>

    /**
     * Shows [task] as progress where [options] says (`location`, `title`, `cancellable`) until the promise it returns
     * settles; with `cancellable`, the editor's Cancel button fires the token it gets.
     */
    fun withProgress(
        options: dynamic,
        task: (progress: dynamic, token: CancellationToken) -> kotlin.js.Promise<Unit>,
    ): Thenable<Unit>

    /** A terminal; with a `pty` in [options] (`{ name, pty, iconPath }`) the extension is its process. */
    fun createTerminal(options: dynamic): dynamic

    val activeTerminal: dynamic

    fun onDidCloseTerminal(listener: (dynamic) -> Unit): Disposable

    fun onDidChangeActiveTerminal(listener: (dynamic) -> Unit): Disposable
}

/** An event source: listeners subscribe through [event], and [fire] calls them. */
external class EventEmitter<T> : Disposable {
    val event: dynamic

    fun fire(data: T)

    override fun dispose()
}

@JsName("tasks")
external object VsCodeTasks {
    fun fetchTasks(): Thenable<Array<dynamic>>

    /** Runs [task]; resolves to its `TaskExecution`, whose `terminate()` stops it. */
    fun executeTask(task: dynamic): Thenable<dynamic>

    /** The event carries `{ execution, exitCode }`. */
    fun onDidEndTaskProcess(listener: (dynamic) -> Unit): Disposable
}

/** A task the extension builds: `new Task(definition, scope, name, source, execution, problemMatchers)`. */
external class Task(
    definition: dynamic,
    scope: dynamic,
    name: String,
    source: String,
    execution: dynamic,
    problemMatchers: dynamic,
)

/** A task execution whose process is the extension's own pseudoterminal, which [callback] resolves. */
external class CustomExecution(callback: (dynamic) -> kotlin.js.Promise<dynamic>)

@JsName("debug")
external object VsCodeDebug {
    /** Starts the launch configuration [nameOrConfiguration] of [folder]; `{ noDebug: true }` runs it plainly. */
    fun startDebugging(folder: dynamic, nameOrConfiguration: dynamic, options: dynamic): Thenable<Boolean>

    fun stopDebugging(session: dynamic): Thenable<Unit>

    /** [factory] is `{ createDebugAdapterTracker(session) }`; a tracker hears every message the adapter sends. */
    fun registerDebugAdapterTrackerFactory(debugType: String, factory: dynamic): Disposable

    fun onDidStartDebugSession(listener: (dynamic) -> Unit): Disposable

    fun onDidTerminateDebugSession(listener: (dynamic) -> Unit): Disposable
}

external interface CancellationToken {
    fun onCancellationRequested(listener: (dynamic) -> Unit): Disposable
}

external object ProgressLocation {
    val Notification: Int
}

/** A `window.createQuickPick()` list: items are `{ label, description, detail, kind }` objects. */
external interface QuickPick : Disposable {
    var title: String
    var placeholder: String
    var items: Array<dynamic>
    var matchOnDescription: Boolean

    /** The items the user has highlighted; the first is the one Enter or a click accepts. */
    val selectedItems: Array<dynamic>

    fun onDidAccept(listener: (dynamic) -> Unit): Disposable

    fun onDidHide(listener: (dynamic) -> Unit): Disposable

    /** A button on an item was clicked; the event carries `{ button, item }`. */
    fun onDidTriggerItemButton(listener: (dynamic) -> Unit): Disposable

    fun show()

    fun hide()
}

/** A status bar entry; the editor shows [text], hovers [tooltip] and runs [command] when it is clicked. */
external interface StatusBarItem : Disposable {
    var text: String

    /** A string or a [MarkdownString]. */
    var tooltip: dynamic

    /** A command id, a `{ title, command, arguments }` object, or `undefined` for no click action. */
    var command: dynamic

    /** A [ThemeColor] (only the status bar's error and warning backgrounds), or `undefined` for none. */
    var backgroundColor: dynamic

    /** What a screen reader says for the item: `{ label, role }`. */
    var accessibilityInformation: dynamic

    fun show()
}

/** A colour from the editor's theme, by its id. */
external class ThemeColor(id: String)

/** A codicon, by its id, where the API takes an icon rather than `$(id)` text. */
external class ThemeIcon(id: String)

/** The alignment constants of `vscode.StatusBarAlignment`. */
external object StatusBarAlignment {
    val Left: Int
    val Right: Int
}

@JsName("commands")
external object VsCodeCommands {
    /** [callback] gets the command's first argument, `undefined` when it has none. */
    fun registerCommand(command: String, callback: (dynamic) -> Unit): Disposable

    fun executeCommand(command: String): Thenable<dynamic>
}

@JsName("workspace")
external object VsCodeWorkspace {
    /** The folders the window has open; `null` when it has none, which is VS Code's "no workspace" state. */
    val workspaceFolders: Array<WorkspaceFolder>?

    /** Every document the editor has open, including ones with unsaved edits. Synchronous, unlike `workspace.fs`. */
    val textDocuments: Array<TextDocument>

    fun applyEdit(edit: WorkspaceEdit): Thenable<Boolean>

    fun openTextDocument(uri: Uri): Thenable<TextDocument>

    fun onDidChangeTextDocument(listener: (dynamic) -> Unit): Disposable

    fun onDidSaveTextDocument(listener: (TextDocument) -> Unit): Disposable

    fun onDidChangeWorkspaceFolders(listener: (dynamic) -> Unit): Disposable

    /** Watches files matching [globPattern] in the open folders; the editor's `files.watcherExclude` applies. */
    fun createFileSystemWatcher(globPattern: String): FileSystemWatcher

    fun getConfiguration(section: String): WorkspaceConfiguration

    /** The [section] as it applies to [scope], a folder's [Uri]: `launch` reads that folder's launch configurations. */
    fun getConfiguration(section: String, scope: Uri): WorkspaceConfiguration
}

external interface FileSystemWatcher : Disposable {
    fun onDidChange(listener: (Uri) -> Unit): Disposable

    fun onDidCreate(listener: (Uri) -> Unit): Disposable

    fun onDidDelete(listener: (Uri) -> Unit): Disposable
}

external interface WorkspaceConfiguration {
    fun get(key: String): dynamic

    /** Writes [key]; [global] writes the user's settings rather than the workspace's. */
    fun update(key: String, value: Any?, global: Boolean): Thenable<Unit>
}

external interface WorkspaceFolder {
    val uri: Uri
    val name: String
}

/** A promise as the editor's API returns it; the companion never blocks on one, it only reacts. */
external interface Thenable<T> {
    fun then(onFulfilled: (T) -> Unit, onRejected: (dynamic) -> Unit): Thenable<T>
}

external interface TextDocument {
    val uri: Uri
    val isDirty: Boolean
    val isUntitled: Boolean

    /** Rises on every change to the document, which is what the companion reports as the file's revision. */
    val version: Int

    fun getText(): String

    fun positionAt(offset: Int): Position

    fun save(): Thenable<Boolean>
}

external interface Position

external class Range(start: Position, end: Position)

external class WorkspaceEdit {
    fun replace(uri: Uri, range: Range, newText: String)
}

/** Markdown the editor renders in a hover; command links run only when [isTrusted] allows them. */
external class MarkdownString(value: String, supportThemeIcons: Boolean) {
    val value: String

    /** `true`, or `{ enabledCommands: [...] }` to allow only those commands. */
    var isTrusted: dynamic
}

external object Uri {
    fun file(path: String): Uri

    fun parse(value: String): Uri

    /** The path in the platform's own form, which the companion normalises to the link's `/`-separated one. */
    val fsPath: String
}

/** The editor's tab strip, which is the only view of what is open that VS Code exposes. */
@JsName("window")
external object VsCodeTabs {
    val tabGroups: TabGroups

    fun showTextDocument(document: TextDocument): Thenable<dynamic>

    fun onDidChangeActiveTextEditor(listener: (dynamic) -> Unit): Disposable
}

external interface TabGroups {
    val all: Array<TabGroup>
    val activeTabGroup: TabGroup

    fun close(tab: Tab): Thenable<Boolean>

    fun onDidChangeTabs(listener: (dynamic) -> Unit): Disposable
}

external interface TabGroup {
    val isActive: Boolean
    val activeTab: Tab?
    val tabs: Array<Tab>
}

external interface Tab {
    val label: String
    val isDirty: Boolean

    /** A `TabInputText` carries the file's `uri`; every other input kind is a tab the companion ignores. */
    val input: dynamic
}

/** The editor's own version, which the phone shows beside the project name. */
external val version: String

@JsName("env")
external object VsCodeEnv {
    /** "Visual Studio Code", "Cursor" or "Devin Desktop": what the phone calls this host. */
    val appName: String

    /** The editor's installed application folder (`resources/app`). */
    val appRoot: String

    /** Opens [target] in the user's default browser. */
    fun openExternal(target: Uri): Thenable<Boolean>
}
