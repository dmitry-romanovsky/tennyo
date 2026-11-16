package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState

internal fun TerminalState.applyTerminalTabsState(
    tabsState: TerminalTabsState,
): TerminalState {
    val mergedTabs = tabsState.tabs.map { incoming ->
        incoming.mergeWithExistingTerminalTab(tabs.firstOrNull { it.id == incoming.id })
    }
    val ids = mergedTabs.mapTo(mutableSetOf()) { it.id }
    val backendSelected = tabsState.selectedTerminalId?.takeIf { it in ids }
        ?: tabsState.tabs.firstOrNull { it.isSelected }?.id?.takeIf { it in ids }
    val pendingSelected = pendingSelectionId?.takeIf { it in ids }
    val selected = pendingSelected
        ?: backendSelected
        ?: selectedTerminalId?.takeIf { it in ids }
        ?: tabsState.tabs.firstOrNull()?.id
    val pendingSelection = pendingSelectionId?.takeUnless { pending ->
        pending == backendSelected || pending !in ids
    }
    val mergedSnapshots = (snapshots + tabsState.tabs.mapNotNull { tab ->
        tab.snapshot?.takeUnless { snapshot ->
            snapshots[tab.id]?.hasTerminalOutput() == true && !snapshot.hasTerminalOutput()
        }?.let { tab.id to it }
    }).filterKeys { it in ids }
    return copy(
        tabs = mergedTabs,
        snapshots = mergedSnapshots,
        snapshotRevisions = snapshotRevisions.filterKeys { it in ids },
        snapshotFreshness = snapshotFreshness.filterKeys { it in ids },
        styleRecoveryTerminalIds = styleRecoveryTerminalIds.filter { it in ids }.toSet(),
        selectedTerminalId = selected?.takeIf { id -> mergedTabs.any { it.id == id } },
        pendingSelectionId = pendingSelection,
    )
}

/** The host streams only the active terminal, so stale frames must not create or update other tabs. */
internal fun TerminalState.shouldApplyFrame(terminalId: String, shown: Boolean): Boolean {
    if (!shown) return false
    val selected = selectedTerminal
    return selected == null || selected.id == terminalId
}

internal fun TerminalState.applyTerminalFrameState(frame: TerminalFrame): TerminalState {
    val currentTabs = tabs
    val frameTerminalId = if (frame.isError && currentTabs.none { it.id == frame.terminalId }) {
        selectedTerminal?.id ?: frame.terminalId
    } else {
        frame.terminalId
    }
    val normalizedFrame = if (frameTerminalId == frame.terminalId) frame else frame.copy(terminalId = frameTerminalId)
    val existingTab = currentTabs.firstOrNull { it.id == normalizedFrame.terminalId }
    val application = applyTerminalFramePayload(
        terminalId = normalizedFrame.terminalId,
        frame = normalizedFrame,
        existingSnapshot = snapshots[normalizedFrame.terminalId] ?: existingTab?.snapshot,
        existingText = existingTab?.text.orEmpty(),
    )
    if (application.recoveryOnly) {
        return copy(
            styleEpoch = application.styleEpoch,
            styleDefinitions = application.styleDefinitions,
            styleRecoveryTerminalIds = application.recoveryTerminalIds,
        )
    }
    val frameSnapshot = application.snapshot
    val updatedTabs = if (currentTabs.any { it.id == normalizedFrame.terminalId }) {
        currentTabs.map { tab ->
            if (tab.id == normalizedFrame.terminalId) tab.withFrame(normalizedFrame, frameSnapshot) else tab
        }
    } else {
        currentTabs + TerminalTab(
            id = normalizedFrame.terminalId,
            title = "",
            cwd = "",
            text = frameSnapshot?.text ?: appendTerminalText(
                "",
                normalizedFrame.append.ifEmpty { normalizedFrame.text }),
            snapshot = frameSnapshot,
        )
    }
    val updatedSnapshots = if (frameSnapshot == null) {
        snapshots
    } else {
        snapshots + (normalizedFrame.terminalId to frameSnapshot)
    }
    val selected = selectionAfterFrame(normalizedFrame.terminalId, frameSnapshot, updatedTabs)
    val revisions = application.revision?.let { revision ->
        snapshotRevisions + (normalizedFrame.terminalId to revision)
    } ?: snapshotRevisions
    return copy(
        tabs = updatedTabs,
        snapshots = updatedSnapshots.filterKeys { id -> updatedTabs.any { it.id == id } },
        snapshotRevisions = revisions.filterKeys { id -> updatedTabs.any { it.id == id } },
        snapshotFreshness = snapshotFreshness.filterKeys { id -> updatedTabs.any { it.id == id } },
        styleEpoch = application.styleEpoch,
        styleDefinitions = application.styleDefinitions,
        styleRecoveryTerminalIds = application.recoveryTerminalIds.filter { id -> updatedTabs.any { it.id == id } }
            .toSet(),
        selectedTerminalId = selected?.takeIf { id -> updatedTabs.any { it.id == id } },
    )
}

/**
 * Which terminal is selected once a frame for [frameTerminalId] arrived: the frame's terminal when nothing (or a closed
 * or empty terminal) was selected, otherwise the selection stays.
 */
private fun TerminalState.selectionAfterFrame(
    frameTerminalId: String,
    frameSnapshot: TerminalSnapshot?,
    updatedTabs: List<TerminalTab>,
): String? {
    val selectedHasNoOutput = selectedTerminal?.hasTerminalOutput() != true
    return when {
        selectedTerminalId == null -> frameTerminalId.takeIf { tabs.isEmpty() }
        updatedTabs.none { it.id == selectedTerminalId } -> frameTerminalId
        selectedTerminalId == frameTerminalId -> selectedTerminalId
        selectedHasNoOutput && frameSnapshot?.text?.isNotEmpty() == true -> frameTerminalId
        else -> selectedTerminalId
    }
}

internal fun TerminalState.upsertCreatedTerminal(tab: TerminalTab): TerminalState =
    copy(
        tabs = tabs.filterNot { it.id == tab.id } + tab,
        selectedTerminalId = tab.id,
    ).let { state ->
        tab.snapshot?.let { snapshot ->
            state.applyTerminalFrameState(TerminalFrame(terminalId = tab.id, snapshot = snapshot))
        } ?: state
    }

internal fun TerminalState.removeTerminal(
    terminalId: String,
    keepAdjacentSelectionPending: Boolean = false,
): TerminalState {
    val closedIndex = tabs.indexOfFirst { it.id == terminalId }
    if (closedIndex == -1) return this

    val remainingTabs = tabs.toMutableList().apply { removeAt(closedIndex) }
    val remainingIds = remainingTabs.mapTo(mutableSetOf()) { it.id }
    val nextSelectedId = when {
        remainingTabs.isEmpty() -> null
        selectedTerminalId != null && selectedTerminalId != terminalId && selectedTerminalId in remainingIds ->
            selectedTerminalId

        else -> remainingTabs.getOrNull(safeSelectedIndex.coerceAtMost(remainingTabs.lastIndex))?.id
    }
    val nextPendingSelectionId = when {
        remainingTabs.isEmpty() -> null
        pendingSelectionId == terminalId -> nextSelectedId
        pendingSelectionId != null && pendingSelectionId in remainingIds -> pendingSelectionId
        keepAdjacentSelectionPending && selectedTerminalId == terminalId -> nextSelectedId
        else -> null
    }

    return copy(
        tabs = remainingTabs,
        snapshots = snapshots - terminalId,
        snapshotRevisions = snapshotRevisions - terminalId,
        snapshotFreshness = snapshotFreshness - terminalId,
        styleRecoveryTerminalIds = styleRecoveryTerminalIds - terminalId,
        selectedTerminalId = nextSelectedId,
        pendingSelectionId = nextPendingSelectionId,
    )
}

internal fun TerminalState.selectedTerminalSnapshot(): TerminalSnapshot {
    val terminal = selectedTerminal
    return terminal?.let { snapshots[it.id] ?: it.snapshot }
        ?: TerminalSnapshot()
}

private fun TerminalTab.mergeWithExistingTerminalTab(existing: TerminalTab?): TerminalTab {
    if (existing == null || !existing.hasTerminalOutput()) {
        return this
    }
    val existingSnapshot = existing.snapshot
    return copy(
        text = existingSnapshot?.text ?: existing.text,
        snapshot = existingSnapshot,
    )
}

private fun TerminalTab.withFrame(
    frame: TerminalFrame,
    frameSnapshot: TerminalSnapshot?,
): TerminalTab =
    if (frameSnapshot == null && !frame.hasTerminalPayload()) {
        this
    } else if (frameSnapshot != null) {
        copy(text = frameSnapshot.text, snapshot = frameSnapshot)
    } else {
        val updatedText = appendTerminalText(text, frame.text)
        copy(text = updatedText, snapshot = snapshot?.copy(text = updatedText))
    }

private fun TerminalTab.hasTerminalOutput(): Boolean =
    snapshot != null || text.isNotEmpty()

private fun TerminalSnapshot.hasTerminalOutput(): Boolean =
    text.isNotEmpty()

private data class TerminalFrameApplication(
    val snapshot: TerminalSnapshot?,
    val revision: Long?,
    val styleEpoch: Long,
    val styleDefinitions: Map<Int, TerminalStyleDefinition>,
    val recoveryTerminalIds: Set<String>,
    val recoveryOnly: Boolean = false,
)

private fun TerminalState.applyTerminalFramePayload(
    terminalId: String,
    frame: TerminalFrame,
    existingSnapshot: TerminalSnapshot?,
    existingText: String,
): TerminalFrameApplication {
    val revision = frame.revision
    val compactPayload = frame.compactStylePayload()
    if (compactPayload.requiresEpochRecovery(currentEpoch = styleEpoch, hasSnapshot = frame.snapshot != null)) {
        return TerminalFrameApplication(
            snapshot = null,
            revision = null,
            styleEpoch = compactPayload.styleEpoch,
            styleDefinitions = emptyMap(),
            recoveryTerminalIds = styleRecoveryTerminalIds + terminalId,
            recoveryOnly = true,
        )
    }
    val cache = terminalStyleCacheFor(compactPayload)
    if (frame.baseRevision != null && snapshotRevisions[terminalId] != frame.baseRevision) {
        return cache.recovery(styleRecoveryTerminalIds + terminalId, revision = null)
    }
    if (cache.hasUnknownStyles(compactPayload)) {
        return cache.recovery(styleRecoveryTerminalIds + terminalId, revision = null)
    }
    val snapshot = frame.toTerminalSnapshot(
        existingSnapshot = existingSnapshot,
        existingText = existingText,
        styleDefinitions = cache.definitions,
        compactPayload = compactPayload,
    )
    return TerminalFrameApplication(
        snapshot = snapshot,
        revision = revision,
        styleEpoch = cache.epoch,
        styleDefinitions = cache.definitions,
        recoveryTerminalIds = styleRecoveryTerminalIds - terminalId,
    )
}

private fun TerminalFrame.toTerminalSnapshot(
    existingSnapshot: TerminalSnapshot?,
    existingText: String,
    styleDefinitions: Map<Int, TerminalStyleDefinition>,
    compactPayload: TerminalCompactPayload,
): TerminalSnapshot? {
    val fullSnapshot = snapshot
    if (fullSnapshot != null) return fullSnapshot.resolvedCompactStyles(styleDefinitions)
    if (!hasTerminalPayload()) return null
    val current = existingSnapshot ?: TerminalSnapshot(text = existingText)
    val compactDelta = usesCompactDelta()
    val dropped = current.dropPrefix(dropPrefixChars.takeIf { compactDelta } ?: 0)
    val incoming = if (compactDelta) append else text
    val cursorUnchanged = cursorOffset == null && cursorVisible == null
    return if (incoming.isEmpty() && cursorUnchanged && compactPayload.styleRuns.isEmpty()) {
        // Only a prefix drop: worth keeping when it moves a revisioned compact stream forward.
        dropped.takeIf { compactDelta && revision != null }
    } else {
        val normalizedIncoming = if (compactDelta) incoming else incoming.normalizedTerminalLineEndings()
        val appendedSpans = compactPayload.styleRuns.toStyleSpans(styleDefinitions, offset = dropped.text.length)
        dropped.copy(
            text = dropped.text + normalizedIncoming,
            cursorOffset = cursorOffset ?: dropped.cursorOffset,
            cursorVisible = cursorVisible ?: dropped.cursorVisible,
            spans = dropped.spans + appendedSpans,
        ).trimToBuffer()
    }
}

private fun TerminalSnapshot.resolvedCompactStyles(
    styleDefinitions: Map<Int, TerminalStyleDefinition>,
): TerminalSnapshot {
    if (styleRuns.isEmpty()) return this
    return copy(
        spans = styleRuns.toStyleSpans(styleDefinitions, offset = 0),
        styles = emptyMap(),
        styleRuns = emptyList(),
    )
}

/** The frame changes what the terminal shows (as opposed to only carrying style definitions). */
internal fun TerminalFrame.hasVisibleTerminalPayload(): Boolean =
    text.isNotEmpty() ||
        append.isNotEmpty() ||
        snapshot != null ||
        dropPrefixChars > 0 ||
        cursorOffset != null ||
        cursorVisible != null ||
        styleRuns.isNotEmpty()

private fun TerminalFrame.hasTerminalPayload(): Boolean = when {
    text.isNotEmpty() -> true
    append.isNotEmpty() -> true
    snapshot != null -> true
    dropPrefixChars > 0 -> true
    cursorOffset != null -> true
    cursorVisible != null -> true
    styleRuns.isNotEmpty() -> true
    else -> false
}

private fun TerminalFrame.usesCompactDelta(): Boolean = when {
    append.isNotEmpty() -> true
    dropPrefixChars > 0 -> true
    cursorOffset != null -> true
    cursorVisible != null -> true
    styleRuns.isNotEmpty() -> true
    styles.isNotEmpty() -> true
    baseRevision != null -> true
    else -> false
}

private fun TerminalFrame.compactStylePayload(): TerminalCompactPayload {
    val frameStyleEpoch = snapshot?.styleEpoch?.takeIf { it != 0L } ?: styleEpoch
    return TerminalCompactPayload(
        styleEpoch = frameStyleEpoch,
        styles = styles + snapshot?.styles.orEmpty(),
        styleRuns = snapshot?.styleRuns ?: styleRuns,
    )
}

private data class TerminalCompactPayload(
    val styleEpoch: Long,
    val styles: Map<Int, TerminalStyleDefinition>,
    val styleRuns: List<TerminalStyleRun>,
) {
    fun usesCompactStyles(): Boolean =
        styleEpoch != 0L || styles.isNotEmpty() || styleRuns.isNotEmpty()

    fun requiresEpochRecovery(currentEpoch: Long, hasSnapshot: Boolean): Boolean =
        !hasSnapshot && currentEpoch != 0L && styleEpoch != 0L && styleEpoch != currentEpoch
}

private data class TerminalStyleCacheUpdate(
    val epoch: Long,
    val definitions: Map<Int, TerminalStyleDefinition>,
) {
    fun hasUnknownStyles(payload: TerminalCompactPayload): Boolean =
        payload.styleRuns.any { run -> run.styleId !in definitions || run.ranges.size % 2 != 0 }

    fun recovery(
        recoveryTerminalIds: Set<String>,
        revision: Long?,
    ): TerminalFrameApplication =
        TerminalFrameApplication(
            snapshot = null,
            revision = revision,
            styleEpoch = epoch,
            styleDefinitions = definitions,
            recoveryTerminalIds = recoveryTerminalIds,
            recoveryOnly = true,
        )
}

private fun TerminalState.terminalStyleCacheFor(
    payload: TerminalCompactPayload,
): TerminalStyleCacheUpdate {
    if (!payload.usesCompactStyles()) {
        return TerminalStyleCacheUpdate(
            epoch = styleEpoch,
            definitions = styleDefinitions,
        )
    }
    val epoch = payload.styleEpoch
    val baseDefinitions = if (epoch != styleEpoch) emptyMap() else styleDefinitions
    return TerminalStyleCacheUpdate(
        epoch = epoch,
        definitions = baseDefinitions + payload.styles,
    )
}

private fun TerminalSnapshot.trimToBuffer(): TerminalSnapshot {
    if (text.length <= MAX_TERMINAL_BUFFER_CHARS) return this
    val drop = text.length - MAX_TERMINAL_BUFFER_CHARS
    return dropPrefix(drop)
}

private fun appendTerminalText(current: String, incoming: String): String {
    val next = current + incoming.normalizedTerminalLineEndings()
    return if (next.length <= MAX_TERMINAL_BUFFER_CHARS) next else next.takeLast(MAX_TERMINAL_BUFFER_CHARS)
}

private fun String.normalizedTerminalLineEndings(): String =
    replace("\r\n", "\n").replace('\r', '\n')

private const val MAX_TERMINAL_BUFFER_CHARS = 60_000
