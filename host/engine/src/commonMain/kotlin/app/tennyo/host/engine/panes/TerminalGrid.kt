package app.tennyo.host.engine.panes

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleSpan

/** One row of a terminal emulator's grid: its cells' text in runs of one style; `null` is the default style. */
data class TerminalRow(val runs: List<TerminalRun>)

data class TerminalRun(val text: String, val style: TerminalStyleDefinition?)

/**
 * The snapshot the phone draws of an emulator's grid, scrollback first. Unstyled blanks end no row and blank rows
 * below the cursor are dropped, as on IntelliJ; past [maxChars] only the newest whole rows are kept. [cursorColumn]
 * counts characters into the cursor's row.
 */
fun terminalGridSnapshot(
    rows: List<TerminalRow>,
    cursorRow: Int,
    cursorColumn: Int,
    maxChars: Int = MAX_TERMINAL_GRID_CHARS,
): TerminalSnapshot {
    val lastRow = rows.indexOfLast { row -> row.runs.any { it.style != null || it.text.isNotBlank() } }
        .coerceAtLeast(cursorRow)
        .coerceAtMost(rows.lastIndex)
    val texts = mutableListOf<String>()
    val spans = mutableListOf<List<TerminalStyleSpan>>()
    for (index in 0..lastRow) {
        val (text, rowSpans) = rows[index].read(padTo = if (index == cursorRow) cursorColumn else 0)
        texts += text
        spans += rowSpans
    }
    var first = texts.size
    var kept = 0
    while (first > 0 && kept + texts[first - 1].length + 1 <= maxChars + 1) {
        first--
        kept += texts[first].length + 1
    }
    val builder = StringBuilder()
    val shifted = mutableListOf<TerminalStyleSpan>()
    var cursorOffset: Int? = null
    for (index in first until texts.size) {
        if (index > first) builder.append('\n')
        val rowStart = builder.length
        if (index == cursorRow) cursorOffset = rowStart + cursorColumn
        spans[index].mapTo(shifted) { it.copy(start = it.start + rowStart, end = it.end + rowStart) }
        builder.append(texts[index])
    }
    return TerminalSnapshot(text = builder.toString(), cursorOffset = cursorOffset, spans = shifted)
}

/** The row's text, its trailing unstyled blanks cut but kept up to [padTo], and its styled runs as spans. */
private fun TerminalRow.read(padTo: Int): Pair<String, List<TerminalStyleSpan>> {
    val text = StringBuilder()
    val spans = mutableListOf<TerminalStyleSpan>()
    var styledEnd = 0
    runs.forEach { run ->
        val start = text.length
        text.append(run.text)
        val style = run.style ?: return@forEach
        spans += TerminalStyleSpan(
            start = start,
            end = text.length,
            foreground = style.foreground,
            background = style.background,
            bold = style.bold,
            italic = style.italic,
            underline = style.underline,
        )
        styledEnd = text.length
    }
    val end = maxOf(text.trimEnd().length, styledEnd, minOf(padTo, text.length))
    return text.substring(0, end).padEnd(padTo) to spans
}

/**
 * The colour of a 256-colour palette entry: VS Code's default ANSI colours for the first sixteen, then the xterm
 * colour cube and grey ramp.
 */
fun ansiPaletteColor(index: Int): String {
    val (red, green, blue) = when {
        index < ANSI_COLORS.size -> return ANSI_COLORS[index]
        index < GREY_RAMP_START -> {
            val cube = index - ANSI_COLORS.size
            val side = CUBE_LEVELS.size
            Triple(CUBE_LEVELS[cube / (side * side)], CUBE_LEVELS[cube / side % side], CUBE_LEVELS[cube % side])
        }
        else -> (GREY_RAMP_FIRST + GREY_RAMP_STEP * (index - GREY_RAMP_START)).let { Triple(it, it, it) }
    }
    return "#" + listOf(red, green, blue).joinToString("") { it.toString(HEX_RADIX).padStart(2, '0').uppercase() }
}

private val ANSI_COLORS = listOf(
    "#000000", "#CD3131", "#0DBC79", "#E5E510", "#2472C8", "#BC3FBC", "#11A8CD", "#E5E5E5",
    "#666666", "#F14C4C", "#23D18B", "#F5F543", "#3B8EEA", "#D670D6", "#29B8DB", "#E5E5E5",
)
private val CUBE_LEVELS = listOf(0, 95, 135, 175, 215, 255)
private const val GREY_RAMP_START = 232
private const val GREY_RAMP_FIRST = 8
private const val GREY_RAMP_STEP = 10
private const val HEX_RADIX = 16
private const val MAX_TERMINAL_GRID_CHARS = 24_000
