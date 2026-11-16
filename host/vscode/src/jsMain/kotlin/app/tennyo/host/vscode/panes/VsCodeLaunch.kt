package app.tennyo.host.vscode.panes

import app.tennyo.contract.LaunchCandidate
import app.tennyo.contract.LaunchConfiguration
import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.host.engine.panes.IdeLaunchPort
import app.tennyo.host.engine.panes.IdeLaunchPortFactory
import app.tennyo.host.engine.panes.LaunchPortLease
import app.tennyo.host.engine.panes.LaunchTabInfo
import app.tennyo.host.engine.panes.LaunchTabOutputSource
import app.tennyo.host.engine.panes.LaunchTabWatcher
import app.tennyo.host.engine.panes.LaunchTabsSnapshot
import app.tennyo.host.engine.panes.StoppedLaunch
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.vscode.api.CustomExecution
import app.tennyo.host.vscode.api.EventEmitter
import app.tennyo.host.vscode.api.Task
import app.tennyo.host.vscode.api.VsCodeDebug
import app.tennyo.host.vscode.api.VsCodeTasks
import app.tennyo.host.vscode.api.VsCodeWorkspace
import kotlin.js.Promise

/**
 * What the phone's Launch pane runs in VS Code: the open folders' launch configurations, run without debugging, and the
 * editor's tasks. A launch configuration's output comes from its debug adapter; a shell or process task runs on a
 * pseudo-terminal of Tennyo's, still in the editor's task terminal with its problem matchers, so its output can be
 * read. [sessions] is the window's session host, told of output and runs as they change.
 *
 * A launch configuration that runs in the integrated terminal (`console`) prints there, not to its adapter, so the
 * phone sees only what the adapter reports. A task its extension runs itself (a custom execution, such as Gradle's)
 * starts and stops from the phone, but its output stays in the editor; VS Code lets no other extension read it.
 */
internal fun vsCodeLaunchFactory(sessions: () -> SessionHost?): IdeLaunchPortFactory = object : IdeLaunchPortFactory {
    private val port by lazy { VsCodeLaunchPort(sessions) }

    override fun create(): LaunchPortLease = LaunchPortLease(port) {}
}

@Suppress("TooManyFunctions")
private class VsCodeLaunchPort(private val sessions: () -> SessionHost?) : IdeLaunchPort {
    private var tasks: List<dynamic> = emptyList()
    private var tasksFetchedAt = 0L
    private var selectedConfigurationId: String? = null
    private val runs = linkedMapOf<String, ConfigurationLaunch>()
    private val watchers = mutableMapOf<String, MutableList<LaunchTabWatcher>>()
    private var selectedTabId: String? = null
    private var nextLaunch = 1

    init {
        refreshTasks()
        VsCodeDebug.onDidStartDebugSession { session ->
            runOf(session)?.debugSession = session
        }
        VsCodeDebug.onDidTerminateDebugSession { session -> runOf(session)?.let { finished(it, exitCode = null) } }
        val trackers = js("{}")
        trackers.createDebugAdapterTracker = { session: dynamic ->
            val run = runOf(session)
            if (run == null) undefined else outputTracker(run)
        }
        VsCodeDebug.registerDebugAdapterTrackerFactory("*", trackers)
        VsCodeTasks.onDidEndTaskProcess { ended ->
            runs.values.firstOrNull { it.execution == ended.execution && it.pty == null }
                ?.let { finished(it, ended.exitCode as Int?) }
        }
    }

    override fun configurations(): LaunchConfigurationsState {
        refreshTasks()
        val running = runs.values.filter { it.isRunning }.map { it.configurationId }.toSet()
        val configurations = (launchConfigurations() + taskConfigurations())
            .map { it.copy(isRunning = it.id in running) }
        return LaunchConfigurationsState(configurations, selectedConfigurationId)
    }

    override fun candidatesForFile(path: String): List<LaunchCandidate> {
        val normalizedPath = path.replace('\\', '/')
        return launchEntries().mapNotNull { (id, _, configuration) ->
            val program = configuration.program as? String ?: return@mapNotNull null
            val expandedProgram = program.replace("\${workspaceFolder}", "").replace('\\', '/')
            if ("\${file}" !in program && expandedProgram.trimStart('/') != normalizedPath) return@mapNotNull null
            LaunchCandidate(id, configuration.name as String, configuration.type as String? ?: "launch")
        }
    }

    override fun selectConfiguration(id: String?) {
        selectedConfigurationId = id
    }

    override fun start(id: String): String? {
        val run = when {
            id.startsWith(LAUNCH_PREFIX) -> startLaunch(id)
            id.startsWith(TASK_PREFIX) -> startTask(id)
            else -> null
        } ?: return null
        selectedTabId = run.id
        changed()
        return run.name
    }

    override fun stop(id: String): List<StoppedLaunch> =
        runs.values.filter { it.configurationId == id && it.isRunning }.map { run ->
            run.stop()
            StoppedLaunch(run.name, run.id)
        }

    override fun tabs(watcher: LaunchTabWatcher): LaunchTabsSnapshot {
        runs.keys.forEach { watch(it, watcher) }
        val tabs = runs.values.map { run ->
            LaunchTabInfo(
                id = run.id,
                configurationId = run.configurationId,
                displayName = run.name,
                runProfileName = null,
                configurationName = run.name,
                isRunning = run.isRunning,
            )
        }
        return LaunchTabsSnapshot(tabs, selectedTabId)
    }

    override fun hasTab(id: String): Boolean = id in runs

    override fun selectTab(id: String) {
        selectedTabId = id
    }

    override fun closeTab(id: String): Boolean {
        val run = runs.remove(id) ?: return false
        if (run.isRunning) run.stop()
        if (selectedTabId == id) selectedTabId = runs.keys.lastOrNull()
        watchers.remove(id)?.forEach { it.onTabClosed(id) }
        return true
    }

    override fun outputSource(id: String, maxChars: Int, watcher: LaunchTabWatcher): LaunchTabOutputSource? {
        val run = runs[id] ?: return null
        watch(id, watcher)
        return LaunchTabOutputSource(run.configurationId, run.isRunning, run.output.toString().takeLast(maxChars))
    }

    private fun watch(id: String, watcher: LaunchTabWatcher) {
        if (watcher.shouldWatch(id)) watchers.getOrPut(id) { mutableListOf() } += watcher
    }

    private fun launchConfigurations(): List<LaunchConfiguration> = launchEntries().map { (id, folder, launch) ->
        LaunchConfiguration(id, launch.name as String, launch.type as String? ?: "launch", folder.name as String)
    }

    /** Every folder's launch configurations, with the ids the phone names them by. */
    private fun launchEntries(): List<Triple<String, dynamic, dynamic>> =
        VsCodeWorkspace.workspaceFolders.orEmpty().flatMap { folder ->
            val launches = VsCodeWorkspace.getConfiguration("launch", folder.uri).get("configurations")
            (launches as? Array<dynamic>).orEmpty().filter { it?.name is String }.map { launch ->
                Triple("$LAUNCH_PREFIX${folder.name}:${launch.name}", folder.asDynamic(), launch)
            }
        }

    private fun taskConfigurations(): List<LaunchConfiguration> = tasks.map { task ->
        LaunchConfiguration(taskId(task), task.name as String, task.source as String, (task.scope?.name as String?))
    }

    private fun refreshTasks() {
        val now = hostWallClockMillis()
        if (now - tasksFetchedAt < TASK_REFRESH_MILLIS) return
        tasksFetchedAt = now
        VsCodeTasks.fetchTasks().then({ fetched ->
            val before = tasks.map(::taskId)
            tasks = fetched.toList()
            if (tasks.map(::taskId) != before) sessions()?.broadcastLaunchConfigurations()
        }, { failure -> LOG.info("VS Code's tasks could not be listed: ${failure?.message}") })
    }

    private fun startLaunch(id: String): ConfigurationLaunch? {
        val (_, folder, launch) = launchEntries().firstOrNull { it.first == id } ?: return null
        val run = newLaunch(id, launch.name as String)
        val configuration = js("Object.assign({}, launch)")
        configuration[RUN_MARKER] = run.id
        val options = js("{}")
        options.noDebug = true
        run.stop = {
            val session: dynamic = run.debugSession
            if (session != null) VsCodeDebug.stopDebugging(session)
        }
        VsCodeDebug.startDebugging(folder, configuration, options).then({ started ->
            if (!started) finished(run, exitCode = null)
        }, { failure ->
            output(run, "${failure?.message ?: "The launch configuration did not start."}\n")
            finished(run, exitCode = null)
        })
        return run
    }

    private fun startTask(id: String): ConfigurationLaunch? {
        val task = tasks.firstOrNull { taskId(it) == id } ?: return null
        val run = newLaunch(id, task.name as String)
        val command = taskCommand(task)
        val runnable = if (command != null && EditorPty.isAvailable) ptyTask(task, command, run) else task
        if (runnable === task) run.stop = { run.execution?.terminate() }
        VsCodeTasks.executeTask(runnable).then({ execution -> run.execution = execution }, { failure ->
            output(run, "${failure?.message ?: "The task did not start."}\n")
            finished(run, exitCode = null)
        })
        return run
    }

    /** [task] again, its command now on a pseudo-terminal of Tennyo's whose output reaches the phone too. */
    private fun ptyTask(task: dynamic, command: TaskCommand, run: ConfigurationLaunch): dynamic {
        val writes = EventEmitter<String>()
        val closes = EventEmitter<Int?>()
        val execution = CustomExecution {
            val terminal = js("{}")
            terminal.onDidWrite = writes.event
            terminal.onDidClose = closes.event
            terminal.open = { dimensions: dynamic ->
                val pty = EditorPty.spawn(
                    file = command.argv.first(),
                    args = command.argv.drop(1),
                    cwd = command.cwd,
                    columns = dimensions?.columns as Int? ?: TASK_COLUMNS,
                    rows = dimensions?.rows as Int? ?: TASK_ROWS,
                )
                run.pty = pty
                pty.onData { data: String ->
                    writes.fire(data)
                    output(run, plainText(data))
                }
                pty.onExit { exit: dynamic ->
                    run.pty = null
                    closes.fire(exit?.exitCode as Int?)
                    finished(run, exit?.exitCode as Int?)
                }
            }
            terminal.close = { runCatching { run.pty?.kill() } }
            terminal.handleInput = { data: String -> run.pty?.write(data) }
            Promise.resolve(terminal)
        }
        run.stop = { runCatching { run.pty?.kill() } }
        val name = task.name as String
        return Task(task.definition, task.scope, name, task.source as String, execution, task.problemMatchers)
    }

    private fun newLaunch(configurationId: String, name: String): ConfigurationLaunch =
        ConfigurationLaunch("vscode-run-${nextLaunch++}", configurationId, name).also { runs[it.id] = it }

    private fun runOf(session: dynamic): ConfigurationLaunch? = runs[session?.configuration?.get(RUN_MARKER) as String?]

    private fun outputTracker(run: ConfigurationLaunch): dynamic {
        val tracker = js("{}")
        tracker.onDidSendMessage = { message: dynamic ->
            if (message.type == "event" && message.event == "output" && message.body?.category != "telemetry") {
                (message.body?.output as String?)?.let { output(run, it) }
            }
        }
        return tracker
    }

    private fun output(run: ConfigurationLaunch, text: String) {
        if (text.isEmpty()) return
        run.output.append(text)
        if (run.output.length > MAX_OUTPUT_CHARS) run.output.deleteRange(0, run.output.length - MAX_OUTPUT_CHARS)
        sessions()?.broadcastLaunchOutput(run.configurationId, text, run.isRunning, run.id)
    }

    private fun finished(run: ConfigurationLaunch, exitCode: Int?) {
        if (!run.isRunning) return
        run.isRunning = false
        val ending = exitCode?.let { "\nProcess finished with exit code $it\n" } ?: "\nProcess finished\n"
        output(run, ending)
        changed()
    }

    private fun changed() {
        sessions()?.broadcastLaunchTabs()
        sessions()?.broadcastLaunchConfigurations()
    }

    private companion object {
        val LOG = HostLogger("VsCodeLaunch")
        const val LAUNCH_PREFIX = "launch:"
        const val TASK_PREFIX = "task:"

        /** Carried in a started launch configuration, so its debug session is known to be this run's. */
        const val RUN_MARKER = "tennyoLaunchId"
        const val TASK_REFRESH_MILLIS = 5_000L
        const val MAX_OUTPUT_CHARS = 80_000
        const val TASK_COLUMNS = 120
        const val TASK_ROWS = 30

        fun taskId(task: dynamic): String = "$TASK_PREFIX${task.source}:${task.name}"
    }
}

/** One start of a configuration, a tab on the phone until it is closed. */
private class ConfigurationLaunch(val id: String, val configurationId: String, val name: String) {
    val output = StringBuilder()
    var isRunning = true
    var debugSession: dynamic = null
    var execution: dynamic = null
    var pty: dynamic = null
    var stop: () -> Unit = {}
}

/** A task's command line and folder, as its shell or process execution gives them. */
private class TaskCommand(val argv: List<String>, val cwd: String)

/** The command a shell or process task runs; `null` for a custom execution, which only its extension can run. */
private fun taskCommand(task: dynamic): TaskCommand? {
    val execution = task.execution ?: return null
    val folder = task.scope?.uri?.fsPath as String? ?: VsCodeWorkspace.workspaceFolders?.firstOrNull()?.uri?.fsPath
        ?: return null
    val expand = { text: String -> text.replace("\${workspaceFolder}", folder) }
    val cwd = (execution.options?.cwd as String?)?.let(expand) ?: folder
    val argv = when {
        execution.process is String ->
            listOf(execution.process as String) + taskArguments(execution.args).map(expand)
        execution.commandLine is String -> EditorPty.shellCommand(expand(execution.commandLine as String))
        execution.command != null -> {
            val words = listOf(taskWord(execution.command)) + taskArguments(execution.args)
            EditorPty.shellCommand(expand(words.joinToString(" ") { if (' ' in it) "\"$it\"" else it }))
        }
        else -> return null
    }
    return TaskCommand(argv, cwd)
}

private fun taskArguments(args: dynamic): List<String> = (args as? Array<dynamic>).orEmpty().map(::taskWord)

/** A task word: a string, or a quoted string's `value`. */
private fun taskWord(word: dynamic): String = word as? String ?: word.value as String

/** A pseudo-terminal's output as console text: escape sequences dropped, line ends plain. */
private fun plainText(data: String): String = data.replace(ANSI_ESCAPE, "").replace("\r\n", "\n").replace("\r", "")

/** Control sequences (CSI), operating-system commands (OSC) and the two-character escapes. */
private val ANSI_ESCAPE = Regex(
    "\u001B\\[[0-?]*[ -/]*[@-~]" + "|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)" + "|\u001B[@-Z\\\\-_]",
)
