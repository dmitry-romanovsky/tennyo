package app.tennyo.mobile.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleSpan
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.TennyoTheme
import kotlin.math.abs

/**
 * The terminal screen of [snapshot], pinned to the bottom while new output arrives unless the user scrolled up; it
 * re-pins once they scroll back down.
 */
@Composable
internal fun TerminalConsoleText(
    terminalId: String,
    snapshot: TerminalSnapshot,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val renderCache = remember(terminalId) { TerminalRenderCache() }
    val lineStyle = terminalTextStyle()
    val lines = remember(
        snapshot.text,
        snapshot.cursorOffset,
        snapshot.cursorVisible,
        snapshot.spans,
    ) {
        (snapshot.takeIf { it.text.isNotEmpty() } ?: TerminalSnapshot(text = placeholder))
            .toScreenLines()
            .map { line -> RenderedTerminalLine(line.stableKey, renderCache.render(line)) }
    }
    FollowNewOutput(terminalId, listState, lines, snapshot.text)
    Surface(modifier = modifier.fillMaxWidth(), shape = Corners.none, color = TERMINAL_BACKGROUND) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
            items(items = lines, key = { it.stableKey }) { line ->
                Text(text = line.text, style = lineStyle, maxLines = 1, overflow = TextOverflow.Visible)
            }
        }
    }
}

/**
 * Keeps the newest line in view while the user has not scrolled away from the bottom; scrolling back down resumes it.
 * The first frame of a terminal always lands at the bottom.
 */
@Composable
private fun FollowNewOutput(
    terminalId: String,
    listState: LazyListState,
    lines: List<RenderedTerminalLine>,
    text: String,
) {
    var autoScroll by remember(terminalId) { mutableStateOf(true) }
    var hasInitialAutoScroll by remember(terminalId) { mutableStateOf(false) }
    var programmaticScrollInProgress by remember(terminalId) { mutableStateOf(false) }
    var userScrollInProgress by remember(terminalId) { mutableStateOf(false) }
    val bottomAnchor = remember(terminalId) { TerminalBottomAnchor() }
    val shouldAnchorToBottom = (autoScroll || !hasInitialAutoScroll) && lines.isNotEmpty()
    SideEffect {
        if (shouldAnchorToBottom && bottomAnchor.lastRequestedLines !== lines) {
            bottomAnchor.lastRequestedLines = lines
            listState.requestScrollToItem(lines.lastIndex)
        }
    }
    LaunchedEffect(terminalId, listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isNearBottom() }.collect { (scrolling, isNearBottom) ->
            if (scrolling && !programmaticScrollInProgress) {
                userScrollInProgress = true
                autoScroll = isNearBottom
            } else if (!scrolling && userScrollInProgress) {
                autoScroll = isNearBottom
                userScrollInProgress = false
            }
        }
    }
    LaunchedEffect(terminalId, listState, lines.size) {
        snapshotFlow { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportEndOffset }.collect {
            if (autoScroll && hasInitialAutoScroll && lines.isNotEmpty()) {
                listState.scrollToLatest(lines.lastIndex) { programmaticScrollInProgress = it }
            }
        }
    }
    LaunchedEffect(terminalId, lines.size, text, autoScroll) {
        if (shouldAnchorToBottom) {
            listState.scrollToLatest(lines.lastIndex) { programmaticScrollInProgress = it }
            hasInitialAutoScroll = true
        }
    }
}

/** Scrolls to [lastIndex], reporting while the scroll is the app's own so it is not mistaken for the user's. */
private suspend fun LazyListState.scrollToLatest(lastIndex: Int, inProgress: (Boolean) -> Unit) {
    inProgress(true)
    try {
        scrollToItem(lastIndex)
    } finally {
        inProgress(false)
    }
}

private fun LazyListState.isNearBottom(): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisibleItem >= totalItems - 2
}

private data class RenderedTerminalLine(
    val stableKey: String,
    val text: AnnotatedString,
)

private class TerminalBottomAnchor {
    var lastRequestedLines: List<RenderedTerminalLine>? = null
}

/** Styled lines by content, least recently used first out: a scrolling terminal redraws the same lines often. */
private class TerminalRenderCache {
    private val values = mutableMapOf<TerminalLineCacheKey, AnnotatedString>()
    private val accessOrder = mutableListOf<TerminalLineCacheKey>()

    fun render(line: TerminalScreenLine): AnnotatedString {
        val key = TerminalLineCacheKey(line.text, line.cursorOffset, line.spanSignature)
        values[key]?.let { cached ->
            accessOrder.remove(key)
            accessOrder += key
            return cached
        }
        val value = line.toAnnotatedString()
        values[key] = value
        accessOrder += key
        while (accessOrder.size > MAX_TERMINAL_RENDER_CACHE_LINES) {
            values.remove(accessOrder.removeAt(0))
        }
        return value
    }
}

private data class TerminalLineCacheKey(
    val text: String,
    val cursorRelativeOffset: Int?,
    val spanSignature: Int,
)

private fun TerminalScreenLine.toAnnotatedString(): AnnotatedString {
    val lineText = displayText
    val builder = AnnotatedString.Builder(lineText)
    spans.forEach { span -> builder.addStyleSafely(span.style.toSpanStyle(), span.start, span.end) }
    if (cursorOffset != null) {
        val cursorStart = cursorOffset.coerceIn(0, lineText.lastIndex.coerceAtLeast(0))
        builder.addStyleSafely(
            style = SpanStyle(color = Color.Black, background = TERMINAL_DEFAULT_FOREGROUND),
            start = cursorStart,
            end = (cursorStart + 1).coerceAtMost(lineText.length),
        )
    }
    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.addStyleSafely(style: SpanStyle, start: Int, end: Int) {
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(0, length)
    if (safeStart < safeEnd) addStyle(style, safeStart, safeEnd)
}

private fun TerminalStyleSpan.toSpanStyle(): SpanStyle =
    SpanStyle(
        color = foreground.toComposeColor().readableTerminalForeground(background.toComposeColor()),
        background = background.toComposeColor() ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )

/** A foreground too close to its background (dark blue on black, say) falls back to the default, to stay readable. */
private fun Color?.readableTerminalForeground(background: Color?): Color =
    when {
        this == null -> Color.Unspecified
        hasLowContrastWith(background ?: Color.Black) -> TERMINAL_DEFAULT_FOREGROUND
        else -> this
    }

private fun Color.hasLowContrastWith(background: Color): Boolean =
    abs(relativeLuminance() - background.relativeLuminance()) < MIN_TERMINAL_COLOR_LUMINANCE_DELTA

/** Rec. 709 luma: how bright a color reads, for telling apart a foreground from its background. */
private fun Color.relativeLuminance(): Float = RED_LUMA * red + GREEN_LUMA * green + BLUE_LUMA * blue

private fun String?.toComposeColor(): Color? = this?.let(::parseCssHexColor)

private fun parseCssHexColor(value: String): Color? {
    val hex = value.removePrefix("#")
    val argb = hex.toLongOrNull(HEX_RADIX) ?: return null
    val red = ((argb shr RED_SHIFT) and CHANNEL_MASK).toInt()
    val green = ((argb shr GREEN_SHIFT) and CHANNEL_MASK).toInt()
    val blue = (argb and CHANNEL_MASK).toInt()
    return when (hex.length) {
        RGB_DIGITS -> Color(red = red, green = green, blue = blue)
        ARGB_DIGITS -> {
            val alpha = ((argb shr ALPHA_SHIFT) and CHANNEL_MASK).toInt()
            Color(red = red, green = green, blue = blue, alpha = alpha)
        }
        else -> null
    }
}

private const val MIN_TERMINAL_COLOR_LUMINANCE_DELTA = 0.18f
private const val RED_LUMA = 0.2126f
private const val GREEN_LUMA = 0.7152f
private const val BLUE_LUMA = 0.0722f
private const val HEX_RADIX = 16
private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFFL
private const val MAX_TERMINAL_RENDER_CACHE_LINES = MAX_TERMINAL_RENDERED_LINES * 3

@Preview(showBackground = true, widthDp = 320, heightDp = 140)
@Composable
private fun TerminalConsoleTextPreview() {
    TennyoTheme {
        Box {
            TerminalConsoleText(
                terminalId = "preview",
                snapshot = TerminalSnapshot(
                    text = "$ user@host:~$ ls -la\ntotal 42\n-rw-r--r-- 1 user staff  1024 Jul 26 main.kt",
                    cursorOffset = 0,
                    cursorVisible = false,
                    spans = emptyList(),
                ),
                placeholder = "Waiting for terminal...",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
