package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalStyleSpan

/** Expands compact style runs into spans, ordered by position; runs naming an unknown style are skipped. */
internal fun List<TerminalStyleRun>.toStyleSpans(
    definitions: Map<Int, TerminalStyleDefinition>,
    offset: Int,
): List<TerminalStyleSpan> {
    if (isEmpty()) return emptyList()
    val spans = ArrayList<TerminalStyleSpan>()
    forEach { run ->
        val definition = definitions[run.styleId] ?: return@forEach
        var index = 0
        while (index + 1 < run.ranges.size) {
            val start = run.ranges[index]
            val end = run.ranges[index + 1]
            if (start < end) {
                spans += definition.toStyleSpan(start + offset, end + offset)
            }
            index += 2
        }
    }
    return spans.sortedWith(compareBy<TerminalStyleSpan> { it.start }.thenBy { it.end })
}

internal fun TerminalStyleDefinition.toStyleSpan(start: Int, end: Int): TerminalStyleSpan =
    TerminalStyleSpan(
        start = start,
        end = end,
        foreground = foreground,
        background = background,
        bold = bold,
        italic = italic,
        underline = underline,
    )

/** Drops the first [dropPrefixChars] characters, moving the cursor and spans with the text that remains. */
internal fun TerminalSnapshot.dropPrefix(dropPrefixChars: Int): TerminalSnapshot {
    val drop = dropPrefixChars.coerceIn(0, text.length)
    if (drop == 0) return this
    val updatedText = text.drop(drop)
    return copy(
        text = updatedText,
        cursorOffset = cursorOffset?.let { (it - drop).coerceIn(0, updatedText.length) },
        spans = spans.shiftAfterDrop(drop, updatedText.length),
    )
}

internal fun List<TerminalStyleSpan>.shiftAfterDrop(
    drop: Int,
    textLength: Int,
): List<TerminalStyleSpan> =
    mapNotNull { span ->
        val start = (span.start - drop).coerceIn(0, textLength)
        val end = (span.end - drop).coerceIn(0, textLength)
        if (start < end) span.copy(start = start, end = end) else null
    }
