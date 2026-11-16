package app.tennyo.mobile.feature.git



sealed interface GitDiffPresentation {
    data class Content(
        val lines: List<Line>,
        val addedLines: Int,
        val deletedLines: Int,
    ) : GitDiffPresentation

    data class Unavailable(val reason: String) : GitDiffPresentation
    data object Binary : GitDiffPresentation
    data object TooLarge : GitDiffPresentation
    data object TooComplex : GitDiffPresentation
    data class Line(val before: String?, val after: String?, val beforeNumber: Int?, val afterNumber: Int?)
}

internal data class GitUnifiedDiffRow(
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val marker: String,
    val text: String,
    val added: Boolean = false,
    val removed: Boolean = false,
    /** Set on a row standing in for [foldedLines] hidden unchanged lines; the index in `lines` where they start. */
    val foldKey: Int? = null,
    val foldedLines: Int = 0,
)

internal const val DIFF_CONTEXT_LINES = 3

/**
 * The rows to show: changed lines with [DIFF_CONTEXT_LINES] of context either side, every longer run of unchanged
 * lines collapsed into one fold row unless its key is in [expandedFolds].
 */
internal fun GitDiffPresentation.Content.unifiedRows(expandedFolds: Set<Int> = emptySet()): List<GitUnifiedDiffRow> {
    val nearChange = BooleanArray(lines.size)
    lines.forEachIndexed { index, line ->
        if (line.before != line.after) {
            for (near in maxOf(0, index - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, index + DIFF_CONTEXT_LINES)) {
                nearChange[near] = true
            }
        }
    }
    return buildList {
        var index = 0
        while (index < lines.size) {
            if (nearChange[index]) {
                addLineRows(lines[index])
                index++
                continue
            }
            val runEnd = (index until lines.size).firstOrNull { nearChange[it] } ?: lines.size
            val count = runEnd - index
            if (count > 1 && index !in expandedFolds) {
                add(GitUnifiedDiffRow(null, null, FOLD_MARKER, "", foldKey = index, foldedLines = count))
            } else {
                for (hidden in index until runEnd) addLineRows(lines[hidden])
            }
            index = runEnd
        }
    }
}

private const val FOLD_MARKER = "⋯"

private fun MutableList<GitUnifiedDiffRow>.addLineRows(line: GitDiffPresentation.Line) {
    if (line.before == line.after) {
        add(GitUnifiedDiffRow(line.beforeNumber, line.afterNumber, " ", line.after.orEmpty()))
        return
    }
    line.before?.let { add(GitUnifiedDiffRow(line.beforeNumber, null, "−", it, removed = true)) }
    line.after?.let { add(GitUnifiedDiffRow(null, line.afterNumber, "+", it, added = true)) }
}

internal const val MAX_MOBILE_DIFF_TOTAL_CHARS = 500_000
internal const val MAX_MOBILE_DIFF_TOTAL_LINES = 10_000
internal const val MAX_MOBILE_DIFF_SEARCH_STEPS = 2_000_000

internal fun GitDiff.toPresentation(
    maxTotalChars: Int = MAX_MOBILE_DIFF_TOTAL_CHARS,
    maxTotalLines: Int = MAX_MOBILE_DIFF_TOTAL_LINES,
    maxSearchSteps: Int = MAX_MOBILE_DIFF_SEARCH_STEPS,
    cancellationCheck: () -> Unit = {},
): GitDiffPresentation = when {
    isBinary -> GitDiffPresentation.Binary
    tooLarge -> GitDiffPresentation.TooLarge
    unavailableReason != null -> GitDiffPresentation.Unavailable(unavailableReason.orEmpty())
    beforeContent.orEmpty().length + afterContent.orEmpty().length > maxTotalChars -> GitDiffPresentation.TooLarge
    else -> {
        val before = beforeContent.orEmpty().toDiffLines()
        val after = afterContent.orEmpty().toDiffLines()
        if (before.size + after.size > maxTotalLines) {
            GitDiffPresentation.TooLarge
        } else {
            calculateDiff(before, after, maxSearchSteps, cancellationCheck)
        }
    }
}

private fun String.toDiffLines(): List<String> = if (isEmpty()) emptyList() else lineSequence().toList()

private fun calculateDiff(
    before: List<String>,
    after: List<String>,
    maxSearchSteps: Int,
    cancellationCheck: () -> Unit,
): GitDiffPresentation {
    val edits = myersEdits(before, after, maxSearchSteps, cancellationCheck)
        ?: return GitDiffPresentation.TooComplex
    val lines = ArrayList<GitDiffPresentation.Line>(maxOf(before.size, after.size))
    var beforeNumber = 1
    var afterNumber = 1
    var addedLines = 0
    var deletedLines = 0
    var index = 0
    while (index < edits.size) {
        cancellationCheck()
        val edit = edits[index]
        if (edit is DiffEdit.Equal) {
            lines += GitDiffPresentation.Line(edit.text, edit.text, beforeNumber++, afterNumber++)
            index++
            continue
        }

        val deleted = mutableListOf<String>()
        val added = mutableListOf<String>()
        while (index < edits.size && edits[index] !is DiffEdit.Equal) {
            when (val changed = edits[index++]) {
                is DiffEdit.Delete -> deleted += changed.text
                is DiffEdit.Insert -> added += changed.text
                is DiffEdit.Equal -> Unit
            }
        }
        repeat(maxOf(deleted.size, added.size)) { changedIndex ->
            val deletedText = deleted.getOrNull(changedIndex)
            val addedText = added.getOrNull(changedIndex)
            lines += GitDiffPresentation.Line(
                before = deletedText,
                after = addedText,
                beforeNumber = deletedText?.let { beforeNumber++ },
                afterNumber = addedText?.let { afterNumber++ },
            )
        }
        deletedLines += deleted.size
        addedLines += added.size
    }
    return GitDiffPresentation.Content(lines, addedLines, deletedLines)
}

// Myers' search ends the moment its step budget runs out or both sequences are consumed; each is its own return.
@Suppress("ReturnCount")
private fun myersEdits(
    before: List<String>,
    after: List<String>,
    maxSearchSteps: Int,
    cancellationCheck: () -> Unit,
): List<DiffEdit>? {
    val maximumDistance = before.size + after.size
    if (maximumDistance == 0) return emptyList()
    val offset = maximumDistance
    val frontier = IntArray(maximumDistance * 2 + 1)
    val trace = ArrayList<IntArray>()
    var steps = 0

    for (distance in 0..maximumDistance) {
        cancellationCheck()
        for (diagonal in -distance..distance step 2) {
            cancellationCheck()
            if (++steps > maxSearchSteps) return null
            val frontierIndex = offset + diagonal
            var beforeIndex = if (
                diagonal == -distance ||
                (diagonal != distance && frontier[frontierIndex - 1] < frontier[frontierIndex + 1])
            ) {
                frontier[frontierIndex + 1]
            } else {
                frontier[frontierIndex - 1] + 1
            }
            var afterIndex = beforeIndex - diagonal
            while (beforeIndex < before.size && afterIndex < after.size && before[beforeIndex] == after[afterIndex]) {
                if (++steps > maxSearchSteps) return null
                beforeIndex++
                afterIndex++
            }
            frontier[frontierIndex] = beforeIndex
            if (beforeIndex == before.size && afterIndex == after.size) {
                trace += frontier.copyOf()
                return backtrackDiff(before, after, trace, offset, cancellationCheck)
            }
        }
        trace += frontier.copyOf()
    }
    return null
}

private fun backtrackDiff(
    before: List<String>,
    after: List<String>,
    trace: List<IntArray>,
    offset: Int,
    cancellationCheck: () -> Unit,
): List<DiffEdit> {
    var beforeIndex = before.size
    var afterIndex = after.size
    val reversed = ArrayList<DiffEdit>(before.size + after.size)

    for (distance in trace.lastIndex downTo 1) {
        cancellationCheck()
        val previousFrontier = trace[distance - 1]
        val diagonal = beforeIndex - afterIndex
        val previousDiagonal = if (
            diagonal == -distance ||
            (diagonal != distance && previousFrontier[offset + diagonal - 1] < previousFrontier[offset + diagonal + 1])
        ) {
            diagonal + 1
        } else {
            diagonal - 1
        }
        val previousBeforeIndex = previousFrontier[offset + previousDiagonal]
        val previousAfterIndex = previousBeforeIndex - previousDiagonal

        while (beforeIndex > previousBeforeIndex && afterIndex > previousAfterIndex) {
            reversed += DiffEdit.Equal(before[--beforeIndex])
            afterIndex--
        }
        if (beforeIndex == previousBeforeIndex) {
            reversed += DiffEdit.Insert(after[--afterIndex])
        } else {
            reversed += DiffEdit.Delete(before[--beforeIndex])
        }
    }
    while (beforeIndex > 0 && afterIndex > 0) {
        reversed += DiffEdit.Equal(before[--beforeIndex])
        afterIndex--
    }
    while (beforeIndex > 0) reversed += DiffEdit.Delete(before[--beforeIndex])
    while (afterIndex > 0) reversed += DiffEdit.Insert(after[--afterIndex])
    return reversed.asReversed()
}

private sealed interface DiffEdit {
    val text: String

    data class Equal(override val text: String) : DiffEdit
    data class Insert(override val text: String) : DiffEdit
    data class Delete(override val text: String) : DiffEdit
}
