package app.tennyo.host.vscode.panes

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalTab
import app.tennyo.host.engine.panes.IdeTerminalPort
import app.tennyo.host.engine.panes.IdeTerminalPortFactory
import app.tennyo.host.engine.panes.TerminalPortCallbacks
import app.tennyo.host.engine.panes.TerminalPortLease
import app.tennyo.host.engine.panes.TerminalRow
import app.tennyo.host.engine.panes.TerminalRun
import app.tennyo.host.engine.panes.ansiPaletteColor
import app.tennyo.host.engine.panes.terminalGridSnapshot
import app.tennyo.host.vscode.api.EventEmitter
import app.tennyo.host.vscode.api.ThemeIcon
import app.tennyo.host.vscode.api.VsCodeWindow

/**
 * Terminals for sessions in this window. VS Code lets an extension read no terminal it did not start, so these are
 * Tennyo's own: each runs the default shell on the editor's `node-pty`, shows in the editor's terminal panel like any
 * other, and keeps its screen in a headless xterm the phone's snapshots are read from. The editor's panel sizes the
 * grid; the phone draws what fits.
 *
 * Terminals the user opened in the editor are not listed; VS Code offers no stable way to read them.
 */
internal fun vsCodeTerminalFactory(root: String): IdeTerminalPortFactory = object : IdeTerminalPortFactory {
    private val terminals by lazy { VsCodeTerminals(root) }

    override fun isAvailable(): Boolean = EditorPty.isAvailable

    override fun create(callbacks: TerminalPortCallbacks): TerminalPortLease? {
        if (!EditorPty.isAvailable) return null
        terminals.listeners += callbacks
        return TerminalPortLease(terminals) { terminals.listeners -= callbacks }
    }
}

private class VsCodeTerminals(private val root: String) : IdeTerminalPort {
    val listeners = mutableListOf<TerminalPortCallbacks>()
    private val terminals = linkedMapOf<String, ShellTerminal>()
    private var selectedId: String? = null
    private var nextId = 1

    init {
        VsCodeWindow.onDidCloseTerminal { closed ->
            val id = terminals.entries.firstOrNull { it.value.editorTerminal == closed }?.key
                ?: return@onDidCloseTerminal
            terminals.remove(id)?.dispose()
            if (selectedId == id) selectedId = terminals.keys.lastOrNull()
            tabsChanged()
        }
        VsCodeWindow.onDidChangeActiveTerminal { active ->
            val id = terminals.entries.firstOrNull { it.value.editorTerminal == active }?.key
            if (id != null && id != selectedId) {
                selectedId = id
                tabsChanged()
            }
        }
    }

    override fun tabs(includeSnapshots: Boolean): List<TerminalTab> =
        terminals.map { (id, terminal) ->
            TerminalTab(
                id = id,
                title = terminal.title,
                cwd = root,
                isSelected = id == selectedId,
                snapshot = if (includeSnapshots) terminal.snapshot() else null,
            )
        }

    override fun snapshot(terminalId: String): TerminalSnapshot? = terminals[terminalId]?.snapshot()

    override fun selectedTerminalId(): String? = selectedId

    /** Shows the selected terminal in the editor, starting one when there is none. */
    override fun ensureOpen(): Boolean {
        val selected = terminals[selectedId]
        if (selected == null) create() else selected.editorTerminal.show(true)
        return terminals.isNotEmpty()
    }

    override fun create(): TerminalTab? {
        val id = "vscode-${nextId++}"
        val terminal = ShellTerminal("Tennyo ${id.removePrefix("vscode-")}", root) { frame(id) }
        terminals[id] = terminal
        selectedId = id
        terminal.editorTerminal.show(true)
        tabsChanged()
        return tabs(includeSnapshots = true).firstOrNull { it.id == id }
    }

    override fun write(terminalId: String, text: String): Boolean = terminals[terminalId]?.write(text) == true

    override fun close(terminalId: String): Boolean {
        val terminal = terminals.remove(terminalId) ?: return false
        terminal.dispose()
        if (selectedId == terminalId) selectedId = terminals.keys.lastOrNull()
        tabsChanged()
        return true
    }

    override fun select(terminalId: String): Boolean {
        val terminal = terminals[terminalId] ?: return false
        selectedId = terminalId
        terminal.editorTerminal.show(true)
        return true
    }

    private fun tabsChanged() = listeners.toList().forEach { it.onTabsChanged() }

    private fun frame(terminalId: String) {
        val readers = listeners.filter { it.shouldReadFrame(terminalId) }
        if (readers.isEmpty()) return
        val snapshot = terminals[terminalId]?.snapshot() ?: return
        readers.forEach { it.onFrame(terminalId, snapshot) }
    }
}

/**
 * One shell on a pseudo-terminal, shown in the editor through a pseudoterminal and mirrored into a headless xterm.
 * The shell starts when the editor opens the terminal, at the panel's size; output is coalesced into frames every
 * [FRAME_MILLIS].
 */
private class ShellTerminal(
    val title: String,
    private val cwd: String,
    private val onFrame: () -> Unit,
) {
    private val screen: dynamic = newHeadlessTerminal()
    private val writes = EventEmitter<String>()
    private val closes = EventEmitter<Int?>()
    private var pty: dynamic = null
    private var frameTimer: dynamic = null
    private var disposed = false

    val editorTerminal: dynamic = VsCodeWindow.createTerminal(terminalOptions())

    fun write(text: String): Boolean {
        val running = pty ?: return false
        running.write(text)
        return true
    }

    fun snapshot(): TerminalSnapshot = readScreen(screen)

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { pty?.kill() }
        editorTerminal.dispose()
        screen.dispose()
    }

    private fun terminalOptions(): dynamic {
        val options = js("{}")
        options.name = title
        options.iconPath = ThemeIcon("device-mobile")
        options.pty = pseudoterminal()
        return options
    }

    private fun pseudoterminal(): dynamic {
        val terminal = js("{}")
        terminal.onDidWrite = writes.event
        terminal.onDidClose = closes.event
        terminal.open = { dimensions: dynamic -> start(dimensions) }
        terminal.close = { dispose() }
        terminal.handleInput = { data: String -> pty?.write(data) }
        terminal.setDimensions = { dimensions: dynamic -> resize(dimensions.columns as Int, dimensions.rows as Int) }
        return terminal
    }

    private fun start(dimensions: dynamic) {
        val columns = dimensions?.columns as Int? ?: DEFAULT_COLUMNS
        val rows = dimensions?.rows as Int? ?: DEFAULT_ROWS
        screen.resize(columns, rows)
        val shell = EditorPty.defaultShell()
        val started = EditorPty.spawn(shell.first(), shell.drop(1), cwd, columns, rows) ?: return closes.fire(1)
        pty = started
        started.onData { data: String ->
            writes.fire(data)
            screen.write(data) { scheduleFrame() }
        }
        started.onExit { exit: dynamic ->
            pty = null
            if (!disposed) closes.fire(exit?.exitCode as Int?)
        }
    }

    private fun resize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        screen.resize(columns, rows)
        runCatching { pty?.resize(columns, rows) }
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameTimer != null || disposed) return
        frameTimer = js("setTimeout")({
            frameTimer = null
            onFrame()
        }, FRAME_MILLIS)
    }

    private companion object {
        const val FRAME_MILLIS = 40
    }
}

private fun newHeadlessTerminal(): dynamic {
    val xterm = js("require('@xterm/headless')")
    val options = js("{}")
    options.cols = DEFAULT_COLUMNS
    options.rows = DEFAULT_ROWS
    options.scrollback = SCROLLBACK_ROWS
    options.allowProposedApi = true
    return js("new xterm.Terminal(options)")
}

/** The headless xterm's buffer as the engine's grid: the newest rows of scrollback and screen, styled per cell. */
private fun readScreen(screen: dynamic): TerminalSnapshot {
    val buffer = screen.buffer.active
    val length = buffer.length as Int
    val first = (length - READ_ROWS).coerceAtLeast(0)
    val cursorRow = (buffer.baseY as Int) + (buffer.cursorY as Int)
    val cursorX = buffer.cursorX as Int
    val columns = screen.cols as Int
    val cell = buffer.getNullCell()
    var cursorColumn = 0
    val rows = (first until length).map { y ->
        val line = buffer.getLine(y) ?: return@map TerminalRow(emptyList())
        val runs = mutableListOf<TerminalRun>()
        val text = StringBuilder()
        var style: TerminalStyleDefinition? = null
        var rowChars = 0
        for (x in 0 until columns) {
            if (y == cursorRow && x == cursorX) cursorColumn = rowChars + text.length
            line.getCell(x, cell)
            if ((cell.getWidth() as Int) == 0) continue
            val cellStyle = cellStyle(cell)
            if (cellStyle != style && text.isNotEmpty()) {
                runs += TerminalRun(text.toString(), style)
                rowChars += text.length
                text.clear()
            }
            style = cellStyle
            text.append((cell.getChars() as String).ifEmpty { " " })
        }
        if (y == cursorRow && cursorX >= columns) cursorColumn = rowChars + text.length
        if (text.isNotEmpty()) runs += TerminalRun(text.toString(), style)
        TerminalRow(runs)
    }
    return terminalGridSnapshot(rows, cursorRow = cursorRow - first, cursorColumn = cursorColumn)
}

private fun cellStyle(cell: dynamic): TerminalStyleDefinition? {
    val foreground = color(cell.isFgRGB() as Boolean, cell.isFgPalette() as Boolean, cell.getFgColor() as Int)
    val background = color(cell.isBgRGB() as Boolean, cell.isBgPalette() as Boolean, cell.getBgColor() as Int)
    val inverse = (cell.isInverse() as Int) != 0
    val style = TerminalStyleDefinition(
        foreground = if (inverse) background ?: INVERSE_FOREGROUND else foreground,
        background = if (inverse) foreground ?: INVERSE_BACKGROUND else background,
        bold = (cell.isBold() as Int) != 0,
        italic = (cell.isItalic() as Int) != 0,
        underline = (cell.isUnderline() as Int) != 0,
    )
    return style.takeIf { it != TerminalStyleDefinition() }
}

private fun color(rgb: Boolean, palette: Boolean, value: Int): String? = when {
    rgb -> "#" + value.toString(HEX_RADIX).padStart(RGB_HEX_DIGITS, '0').uppercase()
    palette -> ansiPaletteColor(value)
    else -> null
}

/** The grid until the editor's panel gives the terminal its size. */
private const val DEFAULT_COLUMNS = 120
private const val DEFAULT_ROWS = 30
private const val SCROLLBACK_ROWS = 1_000
private const val HEX_RADIX = 16
private const val RGB_HEX_DIGITS = 6

/** Rows read per snapshot: more than the engine keeps of a wide grid, far fewer than the whole scrollback. */
private const val READ_ROWS = 400

/** An inverse cell in default colours: the editor's default background drawn on its foreground. */
private const val INVERSE_FOREGROUND = "#1E1E1E"
private const val INVERSE_BACKGROUND = "#CCCCCC"
