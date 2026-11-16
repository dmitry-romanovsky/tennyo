@file:Suppress("UnstableApiUsage")

package app.tennyo.host.intellij.tests

import app.tennyo.contract.TerminalStyleSpan
import app.tennyo.host.intellij.TerminalStyleSpanReader
import app.tennyo.host.intellij.terminalStyleSpansFromHighlighter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.lang.reflect.Proxy
import javax.swing.JPanel

class TerminalStyleSpanReaderTest {
    @Test
    fun `reads the editor of the model inside the view`() {
        val model = terminalOutputModel()
        val viewComponent = JPanel()
        val matchingEditorComponent = JPanel()
        viewComponent.add(matchingEditorComponent)
        val matchingEditor = editor(
            model = model,
            component = matchingEditorComponent,
            highlighter = highlighter(
                listOf(
                    Segment(100, 105, attributes(foreground = Color(0x24, 0x68, 0xAC))),
                )
            ),
        )
        val outsideEditor = editor(
            model = model,
            component = JPanel(),
            highlighter = throwingHighlighter("Outside editor highlighter must not be used"),
        )
        val wrongModelEditor = editor(
            model = terminalOutputModel(),
            component = matchingEditorComponent,
            highlighter = throwingHighlighter("Wrong-model editor highlighter must not be used"),
        )
        val reader = TerminalStyleSpanReader {
            arrayOf(outsideEditor, wrongModelEditor, matchingEditor)
        }

        val spans = reader.readStyleSpans(
            model = model,
            view = terminalView(viewComponent),
            startOffset = 100,
            endOffset = 105,
        )

        assertEquals(
            listOf(
                TerminalStyleSpan(
                    start = 0,
                    end = 5,
                    foreground = "#2468AC",
                    background = null,
                    bold = false,
                    italic = false,
                    underline = false,
                ),
            ),
            spans,
        )
    }

    @Test
    fun `clamps spans to the requested range`() {
        val spans = terminalStyleSpansFromHighlighter(
            startOffset = 10,
            endOffset = 25,
        ) { offset ->
            FakeHighlighterIterator(
                startOffset = offset,
                segments = listOf(
                    Segment(0, 8, attributes(foreground = Color.RED)),
                    Segment(8, 14, attributes(foreground = Color(0x12, 0x34, 0x56))),
                    Segment(14, 20, attributes()),
                    Segment(
                        start = 20,
                        end = 30,
                        attributes = attributes(
                            background = Color(0xAB, 0xCD, 0xEF),
                            fontType = Font.BOLD or Font.ITALIC,
                        ),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(
                TerminalStyleSpan(
                    start = 0,
                    end = 4,
                    foreground = "#123456",
                    background = null,
                    bold = false,
                    italic = false,
                    underline = false,
                ),
                TerminalStyleSpan(
                    start = 10,
                    end = 15,
                    foreground = null,
                    background = "#ABCDEF",
                    bold = true,
                    italic = true,
                    underline = false,
                ),
            ),
            spans,
        )
    }

    @Test
    fun `spans are relative to the range start`() {
        val modelStartOffset = 40
        val visibleStart = 12
        val absoluteVisibleStart = modelStartOffset + visibleStart
        val documentVisibleStart = absoluteVisibleStart - modelStartOffset
        val spans = terminalStyleSpansFromHighlighter(
            startOffset = documentVisibleStart,
            endOffset = documentVisibleStart + 8,
        ) { offset ->
            FakeHighlighterIterator(
                startOffset = offset,
                segments = listOf(
                    Segment(0, documentVisibleStart, attributes(foreground = Color.RED)),
                    Segment(documentVisibleStart, documentVisibleStart + 3, attributes()),
                    Segment(
                        start = documentVisibleStart + 3,
                        end = documentVisibleStart + 4,
                        attributes = attributes(background = Color(0x08, 0x30, 0x42)),
                    ),
                    Segment(
                        start = documentVisibleStart + 4,
                        end = documentVisibleStart + 12,
                        attributes = attributes(foreground = Color(0xEE, 0xDD, 0x99)),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(
                TerminalStyleSpan(
                    start = 3,
                    end = 4,
                    foreground = null,
                    background = "#083042",
                    bold = false,
                    italic = false,
                    underline = false,
                ),
                TerminalStyleSpan(
                    start = 4,
                    end = 8,
                    foreground = "#EEDD99",
                    background = null,
                    bold = false,
                    italic = false,
                    underline = false,
                ),
            ),
            spans,
        )
    }

    @Test
    fun `underline effect marks span underlined`() {
        val spans = spansFor(attributes(effectColor = Color.RED, effectType = EffectType.LINE_UNDERSCORE))

        assertEquals(listOf(span(underline = true)), spans)
    }

    @Test
    fun `non-underline effect is not a style`() {
        val spans = spansFor(attributes(effectColor = Color.RED, effectType = EffectType.BOXED))

        assertTrue(spans.isEmpty())
    }

    @Test
    fun `valid editor is reused across reads`() {
        val model = terminalOutputModel()
        val view = terminalView(JPanel())
        val editor = editor(model, view.component as JPanel, highlighter(emptyList()))
        var scans = 0
        val reader = TerminalStyleSpanReader { scans++; arrayOf(editor) }

        repeat(2) { reader.readStyleSpans(model, view, startOffset = 0, endOffset = 5) }

        assertEquals(1, scans)
    }

    @Test
    fun `disposed editor triggers a rescan`() {
        val model = terminalOutputModel()
        val view = terminalView(JPanel())
        var disposed = false
        val editor = editor(model, view.component as JPanel, highlighter(emptyList())) { disposed }
        var scans = 0
        val reader = TerminalStyleSpanReader { scans++; arrayOf(editor) }

        reader.readStyleSpans(model, view, startOffset = 0, endOffset = 5)
        disposed = true
        reader.readStyleSpans(model, view, startOffset = 0, endOffset = 5)

        assertEquals(2, scans)
    }

    private fun spansFor(attributes: TextAttributes): List<TerminalStyleSpan> =
        terminalStyleSpansFromHighlighter(startOffset = 0, endOffset = 5) { offset ->
            FakeHighlighterIterator(startOffset = offset, segments = listOf(Segment(0, 5, attributes)))
        }

    private fun span(underline: Boolean) = TerminalStyleSpan(
        start = 0,
        end = 5,
        foreground = null,
        background = null,
        bold = false,
        italic = false,
        underline = underline,
    )

    private fun terminalOutputModel(): TerminalOutputModel =
        proxy(TerminalOutputModel::class.java) { _, method, _ ->
            error("Terminal output model method ${method.name} is not used by the style reader test")
        }

    private fun terminalView(component: JPanel): TerminalView =
        proxy(TerminalView::class.java) { _, method, _ ->
            when (method.name) {
                "getComponent" -> component
                else -> error("Terminal view method ${method.name} is not used by the style reader test")
            }
        }

    private fun editor(
        model: TerminalOutputModel,
        component: JPanel,
        highlighter: EditorHighlighter,
        isDisposed: () -> Boolean = { false },
    ): Editor =
        proxy(Editor::class.java) { _, method, args ->
            when (method.name) {
                "getUserData" -> if (args?.firstOrNull() == TerminalOutputModel.KEY) model else null
                "getComponent", "getContentComponent" -> component
                "getHighlighter" -> highlighter
                "isDisposed" -> isDisposed()
                else -> error("Editor method ${method.name} is not used by the style reader test")
            }
        }

    private fun highlighter(segments: List<Segment>): EditorHighlighter =
        proxy(EditorHighlighter::class.java) { _, method, args ->
            when (method.name) {
                "createIterator" -> FakeHighlighterIterator(
                    startOffset = args?.firstOrNull() as Int,
                    segments = segments,
                )

                else -> error("Editor highlighter method ${method.name} is not used by the style reader test")
            }
        }

    private fun throwingHighlighter(message: String): EditorHighlighter =
        proxy(EditorHighlighter::class.java) { _, _, _ -> error(message) }

    private fun <T : Any> proxy(
        type: Class<T>,
        handler: (Any, java.lang.reflect.Method, Array<Any?>?) -> Any?,
    ): T {
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Fake${type.simpleName}@${System.identityHashCode(instance)}"
                else -> handler(instance, method, args)
            }
        }
        return type.cast(proxy)
    }

    private data class Segment(
        val start: Int,
        val end: Int,
        val attributes: TextAttributes,
    )

    private class FakeHighlighterIterator(
        private val segments: List<Segment>,
        startOffset: Int,
    ) : HighlighterIterator {
        private var index = segments.indexOfFirst { segment -> segment.end > startOffset }
            .takeIf { it >= 0 } ?: segments.size

        override fun getTextAttributes(): TextAttributes = segments[index].attributes
        override fun getStart(): Int = segments[index].start
        override fun getEnd(): Int = segments[index].end
        override fun getTokenType(): IElementType = error("Token type is not used by terminal style conversion")
        override fun advance() {
            index += 1
        }

        override fun retreat() {
            index -= 1
        }

        override fun atEnd(): Boolean = index !in segments.indices
        override fun getDocument(): Document = error("Document is not used by terminal style conversion")
    }

    private fun attributes(
        foreground: Color? = null,
        background: Color? = null,
        fontType: Int = Font.PLAIN,
        effectColor: Color? = null,
        effectType: EffectType? = null,
    ): TextAttributes =
        TextAttributes(foreground, background, effectColor, effectType, fontType)
}
