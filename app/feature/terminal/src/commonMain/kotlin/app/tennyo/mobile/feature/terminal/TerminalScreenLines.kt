package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleSpan

/**
 * One line of a terminal screen as it is drawn: trailing grid padding trimmed, the cursor cell located, and the style
 * spans clipped to the line. Pure, so line breaking and cursor placement are tested without Compose.
 */
internal data class TerminalScreenLine(
    val index: Int,
    val text: String,
    val cursorOffset: Int?,
    val spans: List<TerminalLineStyleSpan>,
    /** Differs whenever the drawn line would, so list rows and render caches can key on it. */
    val stableKey: String,
    val spanSignature: Int,
) {
    /** A cursor after the last character is drawn over an extra blank cell. */
    val displayText: String
        get() = if (cursorOffset == text.length) "$text " else text
}

internal data class TerminalLineSlice(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

internal data class TerminalLineStyleSpan(
    val start: Int,
    val end: Int,
    val style: TerminalStyleSpan,
)

private data class TerminalDecoratedLineSlice(
    val slice: TerminalLineSlice,
    val spans: List<TerminalLineStyleSpan>,
)

/** The last [MAX_TERMINAL_RENDERED_LINES] lines of the screen. */
internal fun TerminalSnapshot.toScreenLines(): List<TerminalScreenLine> {
    val paintedCursorOffset = cursorOffset.takeIf { cursorVisible }
    val sourceText = text.trimTrailingTerminalGridBlankLines(paintedCursorOffset, spans)
    val safeCursorOffset = paintedCursorOffset?.coerceIn(0, sourceText.length)
    return sourceText.terminalLineSlices()
        .takeLast(MAX_TERMINAL_RENDERED_LINES)
        .withStyleSpans(spans)
        .map { decorated ->
            val slice = decorated.slice
            val cursorRelativeOffset = slice.cursorRelativeOffset(safeCursorOffset)
            val visibleText = decorated.visibleText(cursorRelativeOffset)
            val lineSpans = decorated.spans.clampToTextLength(visibleText.length)
            val spanSignature = lineSpans.spanSignature()
            TerminalScreenLine(
                index = slice.index,
                text = visibleText,
                cursorOffset = cursorRelativeOffset?.coerceIn(0, visibleText.length),
                spans = lineSpans,
                stableKey = "${slice.index}:${visibleText.hashCode()}:$spanSignature",
                spanSignature = spanSignature,
            )
        }
}

private fun String.terminalLineSlices(): List<TerminalLineSlice> {
    val slices = ArrayList<TerminalLineSlice>()
    var start = 0
    var index = 0
    while (start <= length) {
        val newline = indexOf('\n', start)
        val end = if (newline == -1) length else newline
        slices.add(TerminalLineSlice(index = index, startOffset = start, endOffset = end, text = substring(start, end)))
        if (newline == -1) break
        start = newline + 1
        index += 1
    }
    return slices
}

private fun List<TerminalLineSlice>.withStyleSpans(spans: List<TerminalStyleSpan>): List<TerminalDecoratedLineSlice> {
    if (isEmpty()) return emptyList()
    if (spans.isEmpty()) return map { slice -> TerminalDecoratedLineSlice(slice, emptyList()) }
    val orderedSpans = spans.sortedWith(compareBy<TerminalStyleSpan> { it.start }.thenBy { it.end })
    var firstCandidateIndex = 0
    return map { slice ->
        while (firstCandidateIndex < orderedSpans.size && orderedSpans[firstCandidateIndex].end <= slice.startOffset) {
            firstCandidateIndex += 1
        }
        val lineSpans = ArrayList<TerminalLineStyleSpan>()
        var spanIndex = firstCandidateIndex
        while (spanIndex < orderedSpans.size && orderedSpans[spanIndex].start < slice.endOffset) {
            val span = orderedSpans[spanIndex]
            val localStart = maxOf(span.start, slice.startOffset) - slice.startOffset
            val localEnd = minOf(span.end, slice.endOffset) - slice.startOffset
            if (localStart < localEnd) lineSpans += TerminalLineStyleSpan(localStart, localEnd, span)
            spanIndex += 1
        }
        TerminalDecoratedLineSlice(slice, lineSpans)
    }
}

private fun TerminalLineSlice.cursorRelativeOffset(cursorOffset: Int?): Int? {
    val cursor = cursorOffset ?: return null
    return if (cursor in startOffset..endOffset) (cursor - startOffset).coerceIn(0, text.length) else null
}

/** The IDE sends its whole terminal grid; blank rows below the last content (or cursor, or styled cell) are dropped. */
private fun String.trimTrailingTerminalGridBlankLines(cursorOffset: Int?, spans: List<TerminalStyleSpan>): String {
    val protectedCursor = cursorOffset?.coerceIn(0, length)
    fun lineStartBefore(end: Int) = lastIndexOf('\n', end - 1).let { if (it == -1) 0 else it + 1 }
    fun isDroppable(end: Int): Boolean {
        val lineStart = lineStartBefore(end)
        return (protectedCursor == null || protectedCursor !in lineStart..end) &&
            !spans.hasMeaningfulTerminalStyle(lineStart, end) &&
            substring(lineStart, end).isBlank()
    }
    var end = length
    while (end > 0 && isDroppable(end)) end = (lineStartBefore(end) - 1).coerceAtLeast(0)
    return substring(0, end)
}

private fun TerminalDecoratedLineSlice.visibleText(cursorRelativeOffset: Int?): String {
    fun isPadding(end: Int): Boolean {
        val cellStart = end - 1
        return slice.text[cellStart].isWhitespace() &&
            (cursorRelativeOffset == null || cursorRelativeOffset < cellStart) &&
            !spans.hasMeaningfulLineStyle(cellStart, end)
    }
    var end = slice.text.length
    while (end > 0 && isPadding(end)) end -= 1
    return slice.text.substring(0, end).ifEmpty { " " }
}

private fun List<TerminalStyleSpan>.hasMeaningfulTerminalStyle(start: Int, end: Int): Boolean =
    any { span -> span.start < end && span.end > start && span.hasTerminalStyle() }

private fun List<TerminalLineStyleSpan>.hasMeaningfulLineStyle(start: Int, end: Int): Boolean =
    any { span -> span.start < end && span.end > start && span.style.hasTerminalStyle() }

private fun TerminalStyleSpan.hasTerminalStyle(): Boolean =
    foreground != null || background != null || bold || italic || underline

private fun List<TerminalLineStyleSpan>.clampToTextLength(textLength: Int): List<TerminalLineStyleSpan> {
    if (isEmpty()) return emptyList()
    val endBoundary = textLength.coerceAtLeast(0)
    return mapNotNull { span ->
        val start = span.start.coerceIn(0, endBoundary)
        val end = span.end.coerceIn(0, endBoundary)
        if (start < end) span.copy(start = start, end = end) else null
    }
}

private fun List<TerminalLineStyleSpan>.spanSignature(): Int {
    var signature = 1
    forEach { span -> signature = listOf(signature, span.style.hashCode(), span.start, span.end).hashCode() }
    return signature
}

internal const val MAX_TERMINAL_RENDERED_LINES = 700
