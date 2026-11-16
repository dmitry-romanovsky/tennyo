package app.tennyo.host.intellij

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleSpan
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.activeOutputModel
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalCursorOffsetChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams terminal output to the session: watches each terminal's output model and cursor, coalesces bursts of
 * changes into one frame per [TERMINAL_FRAME_THROTTLE_MILLIS], and renders the frame as a [TerminalSnapshot].
 * [findView] resolves a terminal id to its view on the EDT when a frame fires without one.
 */
internal class IntelliJTerminalOutput(
    private val project: Project,
    private val scheduler: TerminalTasksScheduler,
    private val onFrame: (String, TerminalSnapshot) -> Unit,
    private val shouldReadFrame: (String) -> Boolean,
    private val findView: (String) -> TerminalView?,
) {
    private val styleReader = TerminalStyleSpanReader()
    private val observedOutputModelIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingFrameTerminalIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingFrameModels = ConcurrentHashMap<String, TerminalOutputModel>()
    private val pendingFrameViews = ConcurrentHashMap<String, TerminalView>()

    fun snapshotOf(view: TerminalView): TerminalSnapshot =
        view.activeOutputModel().toSnapshot(view, CURSOR_VISIBLE)

    /** Starts streaming the IDE tab [tab] under [terminalId]; watching stops when the tab's content is disposed. */
    fun observe(terminalId: String, tab: TerminalToolWindowTab) {
        if (scheduler.isDisposed()) return
        val contentId = System.identityHashCode(tab.content)
        val model = tab.view.activeOutputModel()
        if (observedOutputModelIds.add("$contentId:${System.identityHashCode(model)}")) {
            scheduleFrame(terminalId, model = model, view = tab.view, delayMillis = INITIAL_FRAME_DELAY_MILLIS)
            model.addListener(tab.content, frameListener(terminalId, tab.view))
        }
    }

    /** Forgets the output watchers of the tab content [contentId], which the IDE disposed. */
    fun forgetContent(contentId: Int) {
        observedOutputModelIds.removeIf { it.startsWith("$contentId:") }
    }

    private fun forget(terminalId: String) {
        pendingFrameModels.remove(terminalId)
        pendingFrameViews.remove(terminalId)
        pendingFrameTerminalIds.remove(terminalId)
    }

    fun clear() {
        pendingFrameTerminalIds.clear()
        pendingFrameModels.clear()
        pendingFrameViews.clear()
    }

    fun scheduleFrame(
        terminalId: String,
        model: TerminalOutputModel? = null,
        view: TerminalView? = null,
        delayMillis: Int = TERMINAL_FRAME_THROTTLE_MILLIS,
    ) {
        if (scheduler.isDisposed()) return
        if (model != null) pendingFrameModels[terminalId] = model
        if (view != null) pendingFrameViews[terminalId] = view
        if (!pendingFrameTerminalIds.add(terminalId)) return
        if (!scheduler.schedule(delayMillis) { emitCoalescedFrame(terminalId) }) forget(terminalId)
    }

    private fun frameListener(terminalId: String, view: TerminalView) = object : TerminalOutputModelListener {
        override fun afterContentChanged(event: TerminalContentChangeEvent) {
            scheduleFrame(terminalId, model = event.model, view = view)
        }

        override fun cursorOffsetChanged(event: TerminalCursorOffsetChangeEvent) {
            scheduleFrame(terminalId, model = event.model, view = view)
        }
    }

    private fun emitCoalescedFrame(terminalId: String) {
        pendingFrameTerminalIds.remove(terminalId)
        val pendingModel = pendingFrameModels.remove(terminalId)
        val pendingView = pendingFrameViews.remove(terminalId)
        if (scheduler.isDisposed()) return
        if (!shouldReadFrame(terminalId)) {
            HostLoggingPolicy.debug(LOG) { "Tennyo terminal frame skipped by active-pane gate" }
            return
        }
        val snapshot = runCatching {
            invokeOnEdtAndWait {
                val view = pendingView ?: findView(terminalId)
                when {
                    pendingModel != null && view != null ->
                        pendingModel.toSnapshot(view, CURSOR_VISIBLE)

                    view != null -> snapshotOf(view)
                    else -> null
                }
            }
        }.getOrNull() ?: return
        onFrame(terminalId, snapshot)
    }

    private fun TerminalOutputModel.toSnapshot(view: TerminalView, cursorVisible: Boolean): TerminalSnapshot {
        val fullText = getText(startOffset, endOffset).toString()
        val visibleStart = (fullText.length - MAX_TERMINAL_SNAPSHOT_CHARS).coerceAtLeast(0)
        val rawText = fullText.substring(visibleStart)
        val rawCursor = ((cursorOffset - startOffset).toInt() - visibleStart).coerceIn(0, rawText.length)
        val rawSpans = styleReader.readStyleSpans(
            model = this,
            view = view,
            startOffset = visibleStart,
            endOffset = visibleStart + rawText.length,
        )
        val visibleEnd = rawText.visibleTerminalGridEnd(
            cursorOffset = rawCursor.takeIf { cursorVisible },
            spans = rawSpans,
        )
        val text = rawText.substring(0, visibleEnd)
        return TerminalSnapshot(
            text = text,
            cursorOffset = rawCursor.coerceIn(0, text.length),
            cursorVisible = cursorVisible,
            spans = rawSpans.clampToTextLength(text.length),
        )
    }

    private companion object {
        val LOG = Logger.getInstance(IntelliJTerminalOutput::class.java)
        const val MAX_TERMINAL_SNAPSHOT_CHARS = 24_000
        const val TERMINAL_FRAME_THROTTLE_MILLIS = 40
        const val INITIAL_FRAME_DELAY_MILLIS = 20
    }
}

/** Where the terminal grid's content ends: trailing blank, unstyled lines below the cursor are dropped. */
private fun String.visibleTerminalGridEnd(cursorOffset: Int?, spans: List<TerminalStyleSpan>): Int {
    var end = length
    val protectedCursor = cursorOffset?.coerceIn(0, length)
    while (end > 0) {
        val lineStart = lastIndexOf('\n', end - 1).let { if (it == -1) 0 else it + 1 }
        val line = substring(lineStart, end)
        if (protectedCursor != null && protectedCursor in lineStart..end) break
        if (spans.hasMeaningfulTerminalStyle(lineStart, end)) break
        if (line.isNotBlank()) break
        end = (lineStart - 1).coerceAtLeast(0)
    }
    return end
}

private fun List<TerminalStyleSpan>.clampToTextLength(textLength: Int): List<TerminalStyleSpan> {
    if (isEmpty()) return emptyList()
    val endBoundary = textLength.coerceAtLeast(0)
    return mapNotNull { span ->
        val start = span.start.coerceIn(0, endBoundary)
        val end = span.end.coerceIn(0, endBoundary)
        if (start < end) span.copy(start = start, end = end) else null
    }
}

private fun List<TerminalStyleSpan>.hasMeaningfulTerminalStyle(start: Int, end: Int): Boolean =
    any { span -> span.start < end && span.end > start && span.hasTerminalStyle() }

private fun TerminalStyleSpan.hasTerminalStyle(): Boolean =
    foreground != null || background != null || bold || italic || underline

// The IDE keeps the terminal's cursor visibility in a non-public session model; Tennyo reads only public APIs, so the
// phone always draws the cursor.
private const val CURSOR_VISIBLE = true
