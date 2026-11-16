package app.tennyo.mobile.feature.chat

import androidx.compose.ui.text.SpanStyle

/** One block of an agent's Markdown reply; the text inside is still raw, its inline formatting is read at render. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data class CodeFence(val language: String?, val lines: List<String>) : MarkdownBlock

    data class Table(
        val header: List<String>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}

/** The two looks the theme supplies for inline text; bold, italic and strike-through are fixed. */
internal data class InlineStyles(val link: SpanStyle, val code: SpanStyle)

/** [number] is set for ordered items, [checked] for task items. */
data class ListItem(val text: String, val depth: Int, val number: Int?, val checked: Boolean?)

enum class ColumnAlignment { START, CENTER, END }

/**
 * Reads the Markdown agents write: headings, paragraphs, lists, quotes, fences, pipe tables and rules. Tolerant of a
 * reply still streaming in: an open fence runs to the end of the text and a half-written table is a paragraph.
 */
internal fun parseMarkdown(text: String): List<MarkdownBlock> = BlockParser(text.lines()).parse()

private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
private val SETEXT = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
// A backtick fence takes no backtick after its marker, so ```inline``` prose is not a fence.
private val FENCE_OPEN = Regex("""^[ \t]*(`{3,}(?=[^`]*$)|~{3,})[ \t]*([^`\s]*).*$""")
private val QUOTE = Regex("""^ {0,3}>[ ]?(.*)$""")
private val LIST_ITEM = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])[ \t]+(.*)$""")
private val TASK = Regex("""^\[([ xX])](?:[ \t]+(.*))?$""")
private val TABLE_DELIMITER = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")

private const val TAB_WIDTH = 4
private const val CONTINUATION_INDENT = 2

private class BlockParser(private val lines: List<String>) {
    private var pos = 0

    fun parse(): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        while (pos < lines.size) {
            if (lines[pos].isBlank()) {
                pos++
            } else {
                blocks += fence() ?: heading() ?: rule() ?: quote() ?: table() ?: list() ?: paragraph()
            }
        }
        return blocks
    }

    private fun heading(): MarkdownBlock? = HEADING.matchEntire(lines[pos])?.let {
        pos++
        MarkdownBlock.Heading(it.groupValues[1].length, it.groupValues[2].trim())
    }

    private fun rule(): MarkdownBlock? = if (RULE.matches(lines[pos])) {
        pos++
        MarkdownBlock.Rule
    } else {
        null
    }

    private fun fence(): MarkdownBlock? {
        val open = FENCE_OPEN.matchEntire(lines[pos]) ?: return null
        val marker = open.groupValues[1]
        pos++
        val body = mutableListOf<String>()
        while (pos < lines.size && !closesFence(lines[pos], marker)) body += lines[pos++]
        pos++
        return MarkdownBlock.CodeFence(open.groupValues[2].ifEmpty { null }, body)
    }

    private fun closesFence(line: String, marker: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= marker.length && trimmed.all { it == marker[0] }
    }

    private fun quote(): MarkdownBlock? {
        if (!QUOTE.matches(lines[pos])) return null
        val inner = mutableListOf<String>()
        while (pos < lines.size) {
            inner += QUOTE.matchEntire(lines[pos])?.groupValues?.get(1) ?: break
            pos++
        }
        return MarkdownBlock.Quote(BlockParser(inner).parse())
    }

    private fun table(): MarkdownBlock? {
        if (!startsTable(pos)) return null
        val header = splitCells(lines[pos])
        val alignments = splitCells(lines[pos + 1]).map(::alignmentOf)
        pos += 2
        val rows = mutableListOf<List<String>>()
        while (pos < lines.size && '|' in lines[pos]) {
            val cells = splitCells(lines[pos++])
            rows += List(header.size) { cells.getOrElse(it) { "" } }
        }
        return MarkdownBlock.Table(header, alignments, rows)
    }

    private fun startsTable(index: Int): Boolean =
        index + 1 < lines.size &&
            '|' in lines[index] &&
            TABLE_DELIMITER.matches(lines[index + 1]) &&
            splitCells(lines[index]).size == splitCells(lines[index + 1]).size

    private fun list(): MarkdownBlock? {
        if (!isItem(lines[pos])) return null
        val items = mutableListOf<ListItem>()
        val indents = mutableListOf<Int>()
        while (pos < lines.size) {
            val line = lines[pos]
            when {
                isItem(line) && !startsOtherList(line, items, indents) ->
                    items += itemOf(line, indents)
                line.isBlank() && itemFollowsBlanks() -> Unit
                line.isNotBlank() && !isItem(line) && continuesItem(line) ->
                    items[items.lastIndex] = items.last().continuedBy(line)
                else -> break
            }
            pos++
        }
        return MarkdownBlock.ListBlock(ordered = items.first().number != null, items = items)
    }

    private fun ListItem.continuedBy(line: String): ListItem = copy(text = text + " " + line.trim())

    /** An indented line carries on the item above it; an indented fence ends the list and stands as its own block. */
    private fun continuesItem(line: String): Boolean =
        widthOf(line.takeWhile { it == ' ' || it == '\t' }) >= CONTINUATION_INDENT && !FENCE_OPEN.matches(line)

    /** A top-level item of the other kind, bullet or numbered, begins a new list. */
    private fun startsOtherList(line: String, items: List<ListItem>, indents: List<Int>): Boolean {
        val first = items.firstOrNull() ?: return false
        val match = checkNotNull(LIST_ITEM.matchEntire(line))
        val ordered = match.groupValues[2].first().isDigit()
        return ordered != (first.number != null) && widthOf(match.groupValues[1]) <= indents.first()
    }

    private fun isItem(line: String): Boolean = LIST_ITEM.matches(line) && !RULE.matches(line)

    private fun itemFollowsBlanks(): Boolean {
        var next = pos + 1
        while (next < lines.size && lines[next].isBlank()) next++
        return next < lines.size && isItem(lines[next])
    }

    private fun itemOf(line: String, indents: MutableList<Int>): ListItem {
        val match = checkNotNull(LIST_ITEM.matchEntire(line))
        val indent = widthOf(match.groupValues[1])
        while (indents.isNotEmpty() && indent < indents.last()) indents.removeLast()
        if (indents.isEmpty() || indent > indents.last()) indents += indent
        val task = TASK.matchEntire(match.groupValues[3])
        return ListItem(
            text = task?.groupValues?.get(2) ?: match.groupValues[3],
            depth = indents.lastIndex,
            number = match.groupValues[2].dropLast(1).toIntOrNull(),
            checked = task?.let { it.groupValues[1] != " " },
        )
    }

    private fun widthOf(indent: String): Int = indent.map { if (it == '\t') TAB_WIDTH else 1 }.sum()

    private fun paragraph(): MarkdownBlock {
        val parts = mutableListOf<String>()
        while (pos < lines.size && lines[pos].isNotBlank()) {
            val line = lines[pos]
            if (parts.isNotEmpty()) {
                if (SETEXT.matches(line)) {
                    pos++
                    return MarkdownBlock.Heading(if ('=' in line) 1 else 2, joinLines(parts))
                }
                if (interruptsParagraph(line)) break
            }
            parts += line
            pos++
        }
        return MarkdownBlock.Paragraph(joinLines(parts))
    }

    /** A bullet or a list starting at 1 may interrupt a paragraph; "2024. A year" may not. */
    private fun interruptsParagraph(line: String): Boolean =
        FENCE_OPEN.matches(line) || HEADING.matches(line) || RULE.matches(line) || QUOTE.matches(line) ||
            startsTable(pos) || startsList(line)

    private fun startsList(line: String): Boolean {
        val marker = LIST_ITEM.matchEntire(line)?.groupValues?.get(2) ?: return false
        return !marker.first().isDigit() || marker.startsWith("1")
    }

    /** Soft breaks become spaces; two trailing spaces or a backslash make a line break. */
    private fun joinLines(parts: List<String>): String = buildString {
        parts.forEachIndexed { index, line ->
            append(line.trim().removeSuffix("\\").trimEnd())
            if (index < parts.lastIndex) append(if (line.endsWith("  ") || line.endsWith("\\")) '\n' else ' ')
        }
    }
}

private fun alignmentOf(delimiter: String): ColumnAlignment = when {
    delimiter.startsWith(':') && delimiter.endsWith(':') -> ColumnAlignment.CENTER
    delimiter.endsWith(':') -> ColumnAlignment.END
    else -> ColumnAlignment.START
}

/** The cells of one pipe-table row, without the optional outer pipes; `\|` is a literal pipe. */
private fun splitCells(line: String): List<String> {
    val trimmed = line.trim()
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < trimmed.length) {
        val char = trimmed[i]
        when {
            char == '\\' && trimmed.getOrNull(i + 1) == '|' -> {
                cell.append('|')
                i++
            }

            char == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }

            else -> cell.append(char)
        }
        i++
    }
    cells += cell.toString().trim()
    return cells.drop(if (trimmed.startsWith('|')) 1 else 0)
        .dropLast(if (trimmed.endsWith('|') && !trimmed.endsWith("\\|")) 1 else 0)
}
