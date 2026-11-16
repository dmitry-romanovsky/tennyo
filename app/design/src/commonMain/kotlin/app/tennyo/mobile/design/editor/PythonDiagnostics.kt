package app.tennyo.mobile.design.editor

/**
 * Cheap Python checks the editor marks as it highlights: brackets that never close or close the wrong opener, a
 * quote no string closes, and a block statement (`def`, `if`, `for`, …) without its colon.
 *
 * These are line-level heuristics over the highlighter's tokens, not a parser: indentation errors and anything
 * semantic go unmarked.
 */
internal fun pythonErrors(lines: List<String>, tokens: List<List<HighlightSpan>>): Map<Int, List<HighlightSpan>> {
    val scan = PythonScan()
    lines.forEachIndexed { index, line -> scan.line(index, line, tokens[index]) }
    return scan.finish()
}

private class PythonScan {
    private val errors = mutableMapOf<Int, MutableList<HighlightSpan>>()
    private val open = ArrayDeque<OpenBracket>()

    fun line(index: Int, line: String, tokens: List<HighlightSpan>) {
        val quoted = tokens.filter { it.style == HighlightStyle.STRING || it.style == HighlightStyle.COMMENT }
        val depthAtStart = open.size
        val topLevelColon = brackets(index, line, quoted)
        val statementEndsHere = depthAtStart == 0 && open.isEmpty() && !line.trimEnd().endsWith('\\')
        if (statementEndsHere && !topLevelColon && startsBlock(line, quoted)) {
            val indent = line.length - line.trimStart().length
            val codeEnd = quoted.firstOrNull { it.style == HighlightStyle.COMMENT }?.start ?: line.length
            mark(index, indent, codeEnd.coerceIn(indent + 1, line.length))
        }
    }

    fun finish(): Map<Int, List<HighlightSpan>> {
        open.forEach { mark(it.line, it.column, it.column + 1) }
        return errors
    }

    /** Tracks the brackets outside strings and comments; answers whether the line has a colon at depth zero. */
    private fun brackets(index: Int, line: String, quoted: List<HighlightSpan>): Boolean {
        var topLevelColon = false
        var column = 0
        while (column < line.length) {
            val inside = quoted.firstOrNull { column >= it.start && column < it.end }
            if (inside != null) {
                column = inside.end
                continue
            }
            when (val char = line[column]) {
                '(', '[', '{' -> open.addLast(OpenBracket(index, column, char))
                ')', ']', '}' -> close(index, column, char)
                ':' -> topLevelColon = topLevelColon || open.isEmpty()
                // A quote no string pattern could close: everything after it is part of a broken string.
                '"', '\'' -> {
                    mark(index, column, line.length)
                    column = line.length
                }
            }
            column++
        }
        return topLevelColon
    }

    private fun close(index: Int, column: Int, char: Char) {
        if (open.lastOrNull()?.char == OPENER_OF[char]) open.removeLast() else mark(index, column, column + 1)
    }

    /** Whether the line opens a block, and its first word is code rather than the inside of a docstring. */
    private fun startsBlock(line: String, quoted: List<HighlightSpan>): Boolean {
        val word = LEADING_WORD.find(line)?.groupValues?.get(1) ?: return false
        // MatchGroup.range is not common Kotlin; the word starts at the line's first non-blank character.
        val start = line.length - line.trimStart().length
        return word in BLOCK_KEYWORDS && quoted.none { start >= it.start && start < it.end }
    }

    private fun mark(line: Int, start: Int, end: Int) {
        errors.getOrPut(line) { mutableListOf() } += HighlightSpan(start, end, HighlightStyle.ERROR)
    }
}

private data class OpenBracket(val line: Int, val column: Int, val char: Char)

private val OPENER_OF = mapOf(')' to '(', ']' to '[', '}' to '{')

private val LEADING_WORD = Regex("^\\s*([A-Za-z_]+)\\b")

private val BLOCK_KEYWORDS = setOf(
    "def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "async",
)
