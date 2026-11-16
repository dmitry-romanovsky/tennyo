package app.tennyo.mobile.design.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.panScroll
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.design.theme.editorTextStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class EditorLanguage {
    KOTLIN,
    JAVA,
    JAVASCRIPT,
    TYPESCRIPT,
    HTML,
    XML,
    JSON,
    MARKDOWN,
    PROPERTIES,
    PYTHON,
    PLAIN,
}

// The content type and then the extension each answer on their own; only an unknown file reads its text.
@Suppress("ReturnCount")
fun detectEditorLanguage(fileName: String, contentType: String? = null, textSample: String = ""): EditorLanguage {
    contentType?.lowercase()?.let { type ->
        when {
            "json" in type -> return EditorLanguage.JSON
            "html" in type -> return EditorLanguage.HTML
            "xml" in type -> return EditorLanguage.XML
            "markdown" in type -> return EditorLanguage.MARKDOWN
            "javascript" in type -> return EditorLanguage.JAVASCRIPT
            "typescript" in type -> return EditorLanguage.TYPESCRIPT
        }
    }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "kt", "kts" -> EditorLanguage.KOTLIN
        "java" -> EditorLanguage.JAVA
        "js", "jsx", "mjs", "cjs" -> EditorLanguage.JAVASCRIPT
        "ts", "tsx" -> EditorLanguage.TYPESCRIPT
        "html", "htm" -> EditorLanguage.HTML
        "xml", "xsd", "svg" -> EditorLanguage.XML
        "json", "json5" -> EditorLanguage.JSON
        "md", "markdown" -> EditorLanguage.MARKDOWN
        "properties", "gradle", "env", "ini", "cfg", "conf" -> EditorLanguage.PROPERTIES
        "py", "pyw", "pyi" -> EditorLanguage.PYTHON
        else -> detectEditorLanguageFromContent(textSample)
    }
}

private fun detectEditorLanguageFromContent(textSample: String): EditorLanguage {
    // Callers pass the whole file on every recomposition; only its opening decides.
    val start = textSample.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val head = textSample.substring(start, minOf(textSample.length, start + CONTENT_SNIFF_CHARS))
    return when {
        head.startsWith("{") || head.startsWith("[") -> EditorLanguage.JSON
        head.startsWith("<!doctype html", ignoreCase = true) || head.startsWith("<html", ignoreCase = true) ->
            EditorLanguage.HTML
        head.startsWith("<?xml", ignoreCase = true) -> EditorLanguage.XML
        head.startsWith("#!") && "python" in head.substringBefore('\n') -> EditorLanguage.PYTHON
        else -> EditorLanguage.PLAIN
    }
}

/** Whether code editors wrap long lines; the app provides the user's setting at its root. */
val LocalEditorSoftWrap = staticCompositionLocalOf { false }

/**
 * A code editor over [value]. Everything it remembers — caret, scroll, highlighting — belongs to one file: a new
 * [fileId] starts afresh.
 *
 * A tap with the keyboard closed is about to open it, so the caret moves on the way down and is brought into view
 * once the keyboard has made room; a drag scrolls instead and leaves the caret where it was.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    fileId: String? = null,
    language: EditorLanguage = EditorLanguage.PLAIN,
    showLineNumbers: Boolean = true,
    keyboardVisible: Boolean = false,
    softWrap: Boolean = LocalEditorSoftWrap.current,
) {
    val editorId = fileId ?: "editor:${language.name}"
    key(editorId) {
        val textStyle = editorTextStyle()
        var field by remember { mutableStateOf(TextFieldValue(value)) }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        LaunchedEffect(value) {
            if (field.text != value) {
                field = field.copy(
                    text = value,
                    selection = TextRange(
                        field.selection.start.coerceIn(0, value.length),
                        field.selection.end.coerceIn(0, value.length),
                    ),
                    composition = null,
                )
            }
        }
        val highlighting = rememberHighlighting(editorId, value, language)

        val horizontalScroll = rememberScrollState()
        val verticalScroll = rememberScrollState()
        val keyboardShown by rememberUpdatedState(keyboardVisible)
        val imeBottomPx by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current))
        val windowHeightPx by rememberUpdatedState(LocalWindowInfo.current.containerSize.height)
        // Written on every layout pass and read only by effects and gestures, so moving never recomposes.
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        var viewportBottomInWindow by remember { mutableStateOf(0f) }
        var gestureActive by remember { mutableStateOf(false) }
        // The text whose caret should come into view once the keyboard is up; any edit cancels the request.
        var revealCaretIn by remember { mutableStateOf<String?>(null) }

        fun offsetAt(position: Offset): Int? = layout?.getOffsetForPosition(
            position + Offset(horizontalScroll.value.toFloat(), verticalScroll.value.toFloat()),
        )

        fun placeCaret(offset: Int, revealWhenKeyboardOpens: Boolean) {
            field = field.copy(selection = TextRange(offset), composition = null)
            if (revealWhenKeyboardOpens) revealCaretIn = field.text
        }

        LaunchedEffect(Unit) {
            snapshotFlow {
                layout
                    ?.takeIf { revealCaretIn == field.text && keyboardShown && !gestureActive }
                    ?.let { textLayout ->
                        val overlapPx = editorKeyboardOverlapPx(
                            layoutViewportHeight = viewportSize.height,
                            editorBottomInWindow = viewportBottomInWindow,
                            windowHeightPx = windowHeightPx,
                            imeBottomPx = imeBottomPx,
                        )
                        CaretReveal(
                            layout = textLayout,
                            selectionStart = field.selection.start,
                            viewport = editorVisibleViewportSize(viewportSize, keyboardVisible = true, overlapPx),
                            maxHorizontalScroll = horizontalScroll.maxValue,
                            maxVerticalScroll = verticalScroll.maxValue,
                        )
                    }
            }.collectLatest { reveal ->
                reveal ?: return@collectLatest
                // The keyboard resizes the viewport over several frames; each change restarts this wait.
                delay(CARET_REVEAL_SETTLE_MILLIS)
                val target = editorCaretScrollTarget(
                    reveal.layout.caretScrollInput(
                        selectionStart = reveal.selectionStart,
                        viewportSize = reveal.viewport,
                        currentHorizontalScroll = horizontalScroll.value,
                        currentVerticalScroll = verticalScroll.value,
                        maxHorizontalScroll = reveal.maxHorizontalScroll,
                        maxVerticalScroll = reveal.maxVerticalScroll,
                    ),
                )
                val current = EditorCaretScrollTarget(horizontalScroll.value, verticalScroll.value)
                if (target == current) return@collectLatest
                coroutineScope {
                    launch { horizontalScroll.animateScrollTo(target.horizontalScroll) }
                    launch { verticalScroll.animateScrollTo(target.verticalScroll) }
                }
                revealCaretIn = null
            }
        }

        val lineCount = remember(value) { value.count { it == '\n' } + 1 }
        // Wrapped, a logical line can take several rows; only then does the gutter follow the layout.
        val wrappedLayout = if (softWrap) layout else null
        val gutter = remember(lineCount, wrappedLayout) {
            wrappedLayout?.let(::wrappedGutter) ?: (1..lineCount).joinToString("\n")
        }

        BasicTextField(
            value = field,
            onValueChange = { next ->
                val textChanged = next.text != field.text
                field = next
                if (textChanged) onValueChange(next.text)
            },
            modifier = modifier,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            readOnly = readOnly,
            visualTransformation = highlighting,
            onTextLayout = { layout = it },
            decorationBox = { innerTextField ->
                Row {
                    if (showLineNumbers) {
                        // Shares the text's scroll state: the gutter has as many rows, so both agree on its range.
                        Text(
                            text = gutter,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .verticalScroll(verticalScroll),
                            style = textStyle,
                            textAlign = TextAlign.End,
                        )
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            thickness = Borders.thin,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                viewportSize = coordinates.size
                                viewportBottomInWindow = coordinates.boundsInWindow().bottom
                            }
                            .then(
                                // Wrapped text has nothing to scroll sideways to; its width stays the viewport's.
                                if (softWrap) {
                                    Modifier.verticalScroll(verticalScroll)
                                } else {
                                    Modifier.panScroll(verticalScroll, horizontalScroll)
                                },
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    val before = field
                                    val opensKeyboard =
                                        editorTapStartsWithKeyboardClosed(keyboardShown, imeBottomPx)
                                    gestureActive = true
                                    revealCaretIn = null
                                    if (opensKeyboard) offsetAt(down.position)?.let { placeCaret(it, true) }
                                    try {
                                        val tap = awaitTapRelease(down) {
                                            revealCaretIn = null
                                            if (opensKeyboard && field.text == before.text) field = before
                                        }
                                        tap?.let(::offsetAt)?.let { placeCaret(it, opensKeyboard) }
                                    } finally {
                                        gestureActive = false
                                    }
                                }
                            },
                    ) { innerTextField() }
                }
            },
        )
    }
}

/**
 * Highlights [text] once typing settles, and at least every few seconds while it does not. Until a new pass lands,
 * lines left untouched keep their colours and edited ones stay plain.
 */
@Composable
private fun rememberHighlighting(editorId: String, text: String, language: EditorLanguage): VisualTransformation {
    val highlighter = remember { CodeHighlighter() }
    val schedule = remember { EditorHighlightSchedule() }
    var result by remember(language) { mutableStateOf<HighlightResult?>(null) }
    var staleSinceMillis by remember(language) { mutableStateOf<Long?>(null) }

    LaunchedEffect(language, text) {
        if (language == EditorLanguage.PLAIN) return@LaunchedEffect
        val request = HighlightRequest(editorId, revision = text.hashCode().toLong(), text, language)
        val shown = result
        if (shown != null) {
            if (shown.isFreshFor(editorId, request.revision, language)) {
                staleSinceMillis = null
                return@LaunchedEffect
            }
            // Every keystroke restarts this effect, so the change being waited on is always the latest one.
            val nowMillis = Clock.System.now().toEpochMilliseconds()
            val staleSince = staleSinceMillis ?: nowMillis.also { staleSinceMillis = it }
            delay(schedule.delayMillis(pendingSinceMillis = staleSince, lastChangeMillis = nowMillis, nowMillis))
        }
        result = highlighter.highlight(request)
        staleSinceMillis = null
    }

    val palette = EditorHighlightPalette.current()
    return remember(result, palette) {
        result?.let { HighlightTransformation(it, palette) } ?: VisualTransformation.None
    }
}

private class HighlightTransformation(
    private val result: HighlightResult,
    private val palette: EditorHighlightPalette,
) : VisualTransformation {
    // The field filters again on every caret move; only a different text needs new spans.
    private var highlighted = AnnotatedString("")

    override fun filter(text: AnnotatedString): TransformedText {
        if (highlighted.text != text.text) highlighted = highlight(text.text)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    private fun highlight(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        val highlightedLines = result
            .compatibleLinesFor(result.fileId, result.language, text)
            .associateBy { it.index }
        var lineStart = 0
        text.split('\n').forEachIndexed { index, line ->
            highlightedLines[index]?.spans?.forEach { span ->
                val end = span.end.coerceAtMost(line.length)
                if (span.start in 0 until end) {
                    addStyle(palette.styleFor(span.style), lineStart + span.start, lineStart + end)
                }
            }
            lineStart += line.length + 1
        }
    }
}

/**
 * Follows [down] until it lifts and returns where, or null once it has dragged past touch slop or was cancelled.
 * [onDrag] runs once, as the drag begins.
 */
private suspend fun AwaitPointerEventScope.awaitTapRelease(down: PointerInputChange, onDrag: () -> Unit): Offset? {
    var dragging = false
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            ?: return null
        if (!change.pressed) return change.position.takeUnless { dragging }
        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            dragging = true
            onDrag()
        }
    }
}

/** The gutter for wrapped [layout]: each line's number on its first row, blank rows for its continuations. */
internal fun wrappedGutter(layout: TextLayoutResult): String {
    val text = layout.layoutInput.text
    var number = 0
    return (0 until layout.lineCount).joinToString("\n") { row ->
        val start = layout.getLineStart(row)
        if (row == 0 || text.getOrNull(start - 1) == '\n') (++number).toString() else ""
    }
}

private const val CONTENT_SNIFF_CHARS = 256
private const val CARET_REVEAL_SETTLE_MILLIS = 100L
private const val CARET_REVEAL_PADDING_PX = 12f

/** What revealing the caret depends on; any change to it restarts the wait for the keyboard to settle. */
private data class CaretReveal(
    val layout: TextLayoutResult,
    val selectionStart: Int,
    val viewport: IntSize,
    val maxHorizontalScroll: Int,
    val maxVerticalScroll: Int,
)

internal fun editorTapStartsWithKeyboardClosed(
    keyboardVisible: Boolean,
    imeBottomPx: Int,
): Boolean = !keyboardVisible || imeBottomPx <= 0

internal fun editorVisibleViewportSize(
    layoutViewportSize: IntSize,
    keyboardVisible: Boolean,
    keyboardOverlapPx: Int,
): IntSize {
    if (!keyboardVisible || keyboardOverlapPx <= 0) return layoutViewportSize
    return IntSize(
        width = layoutViewportSize.width,
        height = (layoutViewportSize.height - keyboardOverlapPx).coerceAtLeast(0),
    )
}

internal fun editorKeyboardOverlapPx(
    layoutViewportHeight: Int,
    editorBottomInWindow: Float,
    windowHeightPx: Int,
    imeBottomPx: Int,
): Int {
    if (layoutViewportHeight <= 0 || windowHeightPx <= 0 || imeBottomPx <= 0) return 0
    val keyboardTopInWindow = windowHeightPx - imeBottomPx
    return (editorBottomInWindow - keyboardTopInWindow)
        .toInt()
        .coerceIn(0, layoutViewportHeight)
}

internal data class EditorCaretScrollInput(
    val caretLeft: Float,
    val caretRight: Float,
    val contextTop: Float,
    val contextBottom: Float,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val currentHorizontalScroll: Int,
    val currentVerticalScroll: Int,
    val maxHorizontalScroll: Int,
    val maxVerticalScroll: Int,
    val padding: Float = CARET_REVEAL_PADDING_PX,
)

internal data class EditorCaretScrollTarget(
    val horizontalScroll: Int,
    val verticalScroll: Int,
)

internal fun editorCaretScrollTarget(input: EditorCaretScrollInput): EditorCaretScrollTarget {
    if (input.viewportWidth <= 0 || input.viewportHeight <= 0) {
        return EditorCaretScrollTarget(
            horizontalScroll = input.currentHorizontalScroll.coerceIn(0, input.maxHorizontalScroll.coerceAtLeast(0)),
            verticalScroll = input.currentVerticalScroll.coerceIn(0, input.maxVerticalScroll.coerceAtLeast(0)),
        )
    }

    return EditorCaretScrollTarget(
        horizontalScroll = scrollTargetForRange(
            rangeStart = input.caretLeft,
            rangeEnd = input.caretRight,
            viewportSize = input.viewportWidth,
            currentScroll = input.currentHorizontalScroll,
            maxScroll = input.maxHorizontalScroll,
            padding = input.padding,
        ),
        verticalScroll = scrollTargetForRange(
            rangeStart = input.contextTop,
            rangeEnd = input.contextBottom,
            viewportSize = input.viewportHeight,
            currentScroll = input.currentVerticalScroll,
            maxScroll = input.maxVerticalScroll,
            padding = input.padding,
        ),
    )
}

private fun scrollTargetForRange(
    rangeStart: Float,
    rangeEnd: Float,
    viewportSize: Int,
    currentScroll: Int,
    maxScroll: Int,
    padding: Float,
): Int {
    val visibleStart = currentScroll + padding
    val visibleEnd = currentScroll + viewportSize - padding
    val target = when {
        rangeStart < visibleStart -> rangeStart - padding
        rangeEnd > visibleEnd -> rangeEnd + padding - viewportSize
        else -> currentScroll.toFloat()
    }
    return target.toInt().coerceIn(0, maxScroll.coerceAtLeast(0))
}

/** The caret plus a line above and two below: revealing that much keeps the edited code readable around it. */
private fun TextLayoutResult.caretScrollInput(
    selectionStart: Int,
    viewportSize: IntSize,
    currentHorizontalScroll: Int,
    currentVerticalScroll: Int,
    maxHorizontalScroll: Int,
    maxVerticalScroll: Int,
): EditorCaretScrollInput {
    val safeSelection = selectionStart.coerceIn(0, layoutInput.text.text.length)
    val cursorRect = getCursorRect(safeSelection)
    val line = getLineForOffset(safeSelection)
    return EditorCaretScrollInput(
        caretLeft = cursorRect.left,
        caretRight = cursorRect.right,
        contextTop = getLineTop((line - 1).coerceAtLeast(0)),
        contextBottom = getLineBottom((line + 2).coerceAtMost(lineCount - 1)),
        viewportWidth = viewportSize.width,
        viewportHeight = viewportSize.height,
        currentHorizontalScroll = currentHorizontalScroll,
        currentVerticalScroll = currentVerticalScroll,
        maxHorizontalScroll = maxHorizontalScroll,
        maxVerticalScroll = maxVerticalScroll,
    )
}

/**
 * Syntax colours, VS Code's defaults: Dark+ on a dark surface, Light+ on a light one. The error style adds to the
 * token's own colour instead of replacing it, so the mistake still reads as the code it is.
 */
class EditorHighlightPalette private constructor(
    private val keyword: SpanStyle,
    private val function: SpanStyle,
    private val number: SpanStyle,
    private val string: SpanStyle,
    private val key: SpanStyle,
    private val comment: SpanStyle,
    private val type: SpanStyle,
    private val property: SpanStyle,
) {
    fun styleFor(style: HighlightStyle): SpanStyle =
        when (style) {
            HighlightStyle.KEYWORD -> keyword
            HighlightStyle.FUNCTION -> function
            HighlightStyle.NUMBER -> number
            HighlightStyle.STRING -> string
            HighlightStyle.KEY -> key
            HighlightStyle.COMMENT -> comment
            HighlightStyle.TYPE -> type
            HighlightStyle.PROPERTY -> property
            HighlightStyle.ERROR -> error
        }

    companion object {
        private val error = SpanStyle(textDecoration = TextDecoration.Underline, background = Color(229, 72, 77, 56))

        val Dark = EditorHighlightPalette(
            keyword = SpanStyle(color = Color(0xFF569CD6)),
            function = SpanStyle(color = Color(0xFFDCDCAA)),
            number = SpanStyle(color = Color(0xFFB5CEA8)),
            string = SpanStyle(color = Color(0xFFCE9178)),
            key = SpanStyle(color = Color(0xFF9CDCFE)),
            comment = SpanStyle(color = Color(0xFF6A9955)),
            type = SpanStyle(color = Color(0xFF4EC9B0)),
            property = SpanStyle(color = Color(0xFF9CDCFE)),
        )

        val Light = EditorHighlightPalette(
            keyword = SpanStyle(color = Color(0xFF0000FF)),
            function = SpanStyle(color = Color(0xFF795E26)),
            number = SpanStyle(color = Color(0xFF098658)),
            string = SpanStyle(color = Color(0xFFA31515)),
            key = SpanStyle(color = Color(0xFF0451A5)),
            comment = SpanStyle(color = Color(0xFF008000)),
            type = SpanStyle(color = Color(0xFF267F99)),
            property = SpanStyle(color = Color(0xFF001080)),
        )

        /** The palette for the surface code is drawn on. */
        @Composable
        fun current(): EditorHighlightPalette =
            if (MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE) Dark else Light

        private const val DARK_SURFACE_LUMINANCE = 0.5f
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 160)
@Composable
private fun CodeEditorPreview() {
    var text by remember { mutableStateOf("fun hello() {\n    println(\"world\")\n}") }
    TennyoTheme {
        CodeEditor(
            value = text,
            onValueChange = { text = it },
            language = EditorLanguage.KOTLIN,
            showLineNumbers = true,
        )
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 160)
@Composable
private fun CodeEditorReadOnlyPreview() {
    TennyoTheme {
        CodeEditor(
            value = "// Read-only file\nval x = 42",
            onValueChange = {},
            readOnly = true,
            language = EditorLanguage.KOTLIN,
        )
    }
}
