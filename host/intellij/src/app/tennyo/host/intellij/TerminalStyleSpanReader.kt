@file:Suppress("UnstableApiUsage")

package app.tennyo.host.intellij

import app.tennyo.contract.TerminalStyleSpan
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities

internal class TerminalStyleSpanReader(
    private val editors: () -> Array<Editor> = { EditorFactory.getInstance().allEditors },
) {
    @Volatile
    private var lastEditor: Editor? = null

    fun readStyleSpans(
        model: TerminalOutputModel,
        view: TerminalView,
        startOffset: Int,
        endOffset: Int,
    ): List<TerminalStyleSpan> {
        if (startOffset >= endOffset) return emptyList()
        val editor = lastEditor?.takeIf { it.isTerminalEditor(model, view) }
            ?: editors().firstOrNull { it.isTerminalEditor(model, view) }
            ?: return emptyList()
        lastEditor = editor
        return terminalStyleSpansFromHighlighter(
            startOffset = startOffset,
            endOffset = endOffset,
        ) { offset -> editor.highlighter.createIterator(offset) }
    }

    private fun Editor.isTerminalEditor(model: TerminalOutputModel, view: TerminalView): Boolean =
        !isDisposed && getUserData(TerminalOutputModel.KEY) === model && isInside(view)

    private fun Editor.isInside(view: TerminalView): Boolean {
        val viewComponent = view.component
        return component === viewComponent ||
                contentComponent === viewComponent ||
                SwingUtilities.isDescendingFrom(component, viewComponent) ||
                SwingUtilities.isDescendingFrom(contentComponent, viewComponent)
    }
}

internal fun terminalStyleSpansFromHighlighter(
    startOffset: Int,
    endOffset: Int,
    createIterator: (Int) -> HighlighterIterator,
): List<TerminalStyleSpan> {
    if (startOffset >= endOffset) return emptyList()
    val iterator = createIterator(startOffset)
    val spans = ArrayList<TerminalStyleSpan>()
    while (!iterator.atEnd() && iterator.start < endOffset) {
        val start = iterator.start.coerceAtLeast(startOffset)
        val end = iterator.end.coerceAtMost(endOffset)
        if (start < end) {
            iterator.textAttributes.toRemoteTerminalStyleSpan(
                start = start - startOffset,
                end = end - startOffset,
            )?.let(spans::add)
        }
        iterator.advance()
    }
    return spans
}

private const val RGB_MASK = 0xFFFFFF

private val UNDERLINE_EFFECTS = setOf(
    EffectType.LINE_UNDERSCORE,
    EffectType.BOLD_LINE_UNDERSCORE,
    EffectType.WAVE_UNDERSCORE,
    EffectType.BOLD_DOTTED_LINE,
)

private fun TextAttributes.toRemoteTerminalStyleSpan(
    start: Int,
    end: Int,
): TerminalStyleSpan? {
    val foreground = foregroundColor?.toHexColor()
    val background = backgroundColor?.toHexColor()
    val bold = fontType and Font.BOLD != 0
    val italic = fontType and Font.ITALIC != 0
    val underline = effectColor != null && effectType in UNDERLINE_EFFECTS
    val hasColor = foreground != null || background != null
    val hasEmphasis = bold || italic || underline
    if (!hasColor && !hasEmphasis) return null
    return TerminalStyleSpan(
        start = start,
        end = end,
        foreground = foreground,
        background = background,
        bold = bold,
        italic = italic,
        underline = underline,
    )
}

private fun Color.toHexColor(): String =
    "#%06X".format(rgb and RGB_MASK)
