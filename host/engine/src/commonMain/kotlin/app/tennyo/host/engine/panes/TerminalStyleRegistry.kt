package app.tennyo.host.engine.panes

import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalStyleSpan
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

class TerminalStyleRegistry {
    private val monitor = HostMonitor()
    private var nextStyleId = 1
    private val idsByDefinition = LinkedHashMap<TerminalStyleDefinition, Int>()
    private val definitionsById = LinkedHashMap<Int, TerminalStyleDefinition>()
    private val announcedStyleIds = mutableSetOf<Int>()

    val styleEpoch: Long = 1L

    fun compactSnapshot(
        snapshot: TerminalSnapshot,
        includeAllDefinitions: Boolean,
    ): TerminalSnapshot {
        val styles = encodeStyleRuns(
            spans = snapshot.spans,
            rangeStart = 0,
            rangeEnd = snapshot.text.length,
            includeAllDefinitions = includeAllDefinitions,
        )
        return snapshot.copy(
            spans = emptyList(),
            styleEpoch = styleEpoch,
            styles = styles.definitions,
            styleRuns = styles.runs,
        )
    }

    fun compactFrameForSnapshot(
        terminalId: String,
        previous: TerminalSnapshot?,
        snapshot: TerminalSnapshot,
        baseRevision: Long?,
        revision: Long?,
        maxDeltaChars: Int,
    ): TerminalFrame {
        if (previous == null || baseRevision == null) {
            return fullSnapshotFrame(terminalId, snapshot, revision)
        }

        val retainedPrefixLength = retainedPrefixLength(previous.text, snapshot.text)
            ?: return fullSnapshotFrame(terminalId, snapshot, revision)
        val dropPrefixChars = previous.text.length - retainedPrefixLength
        val append = snapshot.text.substring(retainedPrefixLength)
        if (append.length > maxDeltaChars) {
            return fullSnapshotFrame(terminalId, snapshot, revision)
        }
        if (dropPrefixChars > 0 && retainedPrefixLength == 0 && previous.text.isNotEmpty()) {
            return fullSnapshotFrame(terminalId, snapshot, revision)
        }
        if (!retainedStyleSpansMatch(previous, snapshot, dropPrefixChars, retainedPrefixLength)) {
            return fullSnapshotFrame(terminalId, snapshot, revision)
        }

        val styles = encodeStyleRuns(
            spans = snapshot.spans,
            rangeStart = retainedPrefixLength,
            rangeEnd = snapshot.text.length,
            includeAllDefinitions = false,
        )
        return TerminalFrame(
            terminalId = terminalId,
            baseRevision = baseRevision,
            revision = revision,
            styleEpoch = styleEpoch,
            dropPrefixChars = dropPrefixChars,
            append = append,
            cursorOffset = snapshot.cursorOffset,
            cursorVisible = snapshot.cursorVisible,
            styles = styles.definitions,
            styleRuns = styles.runs,
            isError = false,
        )
    }

    private fun fullSnapshotFrame(
        terminalId: String,
        snapshot: TerminalSnapshot,
        revision: Long?,
    ): TerminalFrame {
        val compactSnapshot = compactSnapshot(snapshot, includeAllDefinitions = false)
        return TerminalFrame(
            terminalId = terminalId,
            isError = false,
            snapshot = compactSnapshot,
            revision = revision,
            styleEpoch = compactSnapshot.styleEpoch,
            cursorVisible = snapshot.cursorVisible,
        )
    }

    private fun encodeStyleRuns(
        spans: List<TerminalStyleSpan>,
        rangeStart: Int,
        rangeEnd: Int,
        includeAllDefinitions: Boolean,
    ): EncodedTerminalStyles {
        if (spans.isEmpty() || rangeStart >= rangeEnd) {
            return EncodedTerminalStyles()
        }
        val rangesByStyleId = linkedMapOf<Int, MutableList<Int>>()
        spans.sortedWith(compareBy<TerminalStyleSpan> { it.start }.thenBy { it.end })
            .forEach { span ->
                val start = span.start.coerceAtLeast(rangeStart)
                val end = span.end.coerceAtMost(rangeEnd)
                if (start >= end) return@forEach
                val definition = span.toStyleDefinition()
                if (!definition.hasTerminalStyle()) return@forEach
                val styleId = styleId(definition)
                val ranges = rangesByStyleId.getOrPut(styleId) { mutableListOf() }
                ranges.addMergedRange(start - rangeStart, end - rangeStart)
            }
        if (rangesByStyleId.isEmpty()) {
            return EncodedTerminalStyles()
        }
        val definitions = linkedMapOf<Int, TerminalStyleDefinition>()
        rangesByStyleId.keys.forEach { styleId ->
            if (includeAllDefinitions || monitor.guarded { announcedStyleIds.add(styleId) }) {
                monitor.guarded { definitionsById[styleId] }?.let { definition -> definitions[styleId] = definition }
            }
        }
        return EncodedTerminalStyles(
            definitions = definitions,
            runs = rangesByStyleId.map { (styleId, ranges) ->
                TerminalStyleRun(styleId = styleId, ranges = ranges)
            },
        )
    }

    private fun styleId(definition: TerminalStyleDefinition): Int =
        monitor.guarded {
            idsByDefinition[definition] ?: nextStyleId++.also { styleId ->
                idsByDefinition[definition] = styleId
                definitionsById[styleId] = definition
            }
        }
}

fun TerminalStyleSpan.toStyleDefinition(): TerminalStyleDefinition =
    TerminalStyleDefinition(
        foreground = foreground,
        background = background,
        bold = bold,
        italic = italic,
        underline = underline,
    )

private fun TerminalStyleDefinition.hasTerminalStyle(): Boolean =
    foreground != null || background != null || bold || italic || underline

private data class EncodedTerminalStyles(
    val definitions: Map<Int, TerminalStyleDefinition> = emptyMap(),
    val runs: List<TerminalStyleRun> = emptyList(),
)

private fun retainedPrefixLength(previousText: String, snapshotText: String): Int? {
    if (snapshotText.startsWith(previousText)) return previousText.length
    val maxRetained = minOf(previousText.length, snapshotText.length)
    for (length in maxRetained downTo 1) {
        if (previousText.regionMatches(previousText.length - length, snapshotText, 0, length)) {
            return length
        }
    }
    return 0.takeIf { previousText.isEmpty() }
}

private fun retainedStyleSpansMatch(
    previous: TerminalSnapshot,
    snapshot: TerminalSnapshot,
    dropPrefixChars: Int,
    retainedPrefixLength: Int,
): Boolean {
    val previousRetained = previous.spans.normalizedStyleSpans(
        rangeStart = dropPrefixChars,
        rangeEnd = previous.text.length,
        relativeOffset = dropPrefixChars,
    )
    val currentRetained = snapshot.spans.normalizedStyleSpans(
        rangeStart = 0,
        rangeEnd = retainedPrefixLength,
        relativeOffset = 0,
    )
    return previousRetained == currentRetained
}

private fun List<TerminalStyleSpan>.normalizedStyleSpans(
    rangeStart: Int,
    rangeEnd: Int,
    relativeOffset: Int,
): List<TerminalStyleSpan> =
    mapNotNull { span ->
        val start = span.start.coerceAtLeast(rangeStart)
        val end = span.end.coerceAtMost(rangeEnd)
        if (start >= end) {
            null
        } else {
            span.copy(start = start - relativeOffset, end = end - relativeOffset)
        }
    }.sortedWith(compareBy<TerminalStyleSpan> { it.start }.thenBy { it.end }
        .thenBy { it.toStyleDefinition().hashCode() })

private fun MutableList<Int>.addMergedRange(start: Int, end: Int) {
    if (isNotEmpty()) {
        val lastStartIndex = size - 2
        val lastEndIndex = size - 1
        if (lastStartIndex >= 0 && start <= this[lastEndIndex]) {
            this[lastEndIndex] = maxOf(this[lastEndIndex], end)
            return
        }
    }
    add(start)
    add(end)
}
