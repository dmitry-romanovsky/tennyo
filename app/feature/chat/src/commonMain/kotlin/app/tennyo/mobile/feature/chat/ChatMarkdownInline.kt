package app.tennyo.mobile.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Inline Markdown as styled text: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](url)` and bare http(s)
 * URLs, with backslash escapes. A marker that does not close stays literal.
 */
internal fun inlineMarkdown(text: String, styles: InlineStyles): AnnotatedString =
    buildAnnotatedString { InlineWriter(this, styles).write(text) }

private const val ESCAPABLE = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private val LINK_SCHEMES = listOf("https://", "http://", "mailto:")
private const val URL_TRAILING_PUNCTUATION = ".,;:!?'\"*_"

private class InlineWriter(private val out: AnnotatedString.Builder, private val styles: InlineStyles) {
    fun write(s: String) {
        var i = 0
        while (i < s.length) i = step(s, i)
    }

    private fun step(s: String, i: Int): Int {
        val next = when (s[i]) {
            '\\' -> escape(s, i)
            '`' -> codeSpan(s, i)
            '*', '_', '~' -> emphasis(s, i)
            '[' -> link(s, i)
            'h' -> bareUrl(s, i)
            else -> null
        }
        return next ?: run {
            out.append(s[i])
            i + 1
        }
    }

    private fun escape(s: String, i: Int): Int? {
        val escaped = s.getOrNull(i + 1)?.takeIf { it in ESCAPABLE } ?: return null
        out.append(escaped)
        return i + 2
    }

    private fun codeSpan(s: String, i: Int): Int {
        val length = runLength(s, i, '`')
        val close = codeSpanEnd(s, i)
        if (close == null) {
            out.append("`".repeat(length))
            return i + length
        }
        val raw = s.substring(i + length, close)
        val content = if (raw.length > 2 && raw.startsWith(' ') && raw.endsWith(' ')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        out.pushStyle(styles.code)
        out.append(content)
        out.pop()
        return close + length
    }

    private fun emphasis(s: String, i: Int): Int? {
        val marker = s[i]
        val length = when {
            runLength(s, i, marker) >= 2 -> 2
            marker == '~' -> return null
            else -> 1
        }
        val innerStart = i + length
        if (!opensEmphasis(s, i, innerStart)) return null
        val close = closingMarker(s, innerStart, marker, length) ?: return null
        val style = when {
            marker == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            length == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }
        out.pushStyle(style)
        write(s.substring(innerStart, close))
        out.pop()
        return close + length
    }

    /** Content must follow the marker directly, and an underscore never opens inside a word. */
    private fun opensEmphasis(s: String, at: Int, innerStart: Int): Boolean {
        if (s.getOrNull(innerStart)?.isWhitespace() != false) return false
        return s[at] != '_' || at == 0 || !s[at - 1].isLetterOrDigit()
    }

    /** Where the marker closes, skipping code spans and escapes; a longer run closes with its last [length] marks. */
    private fun closingMarker(s: String, from: Int, marker: Char, length: Int): Int? {
        var j = from
        while (j < s.length) {
            val run = runLength(s, j, marker)
            j = when {
                s[j] == '\\' -> j + 2
                s[j] == '`' -> codeSpanEnd(s, j)?.plus(runLength(s, j, '`')) ?: (j + runLength(s, j, '`'))
                run == 0 -> j + 1
                run >= length && (length == 2 || run == 1) && closes(s, j, run, marker, from) -> return j + run - length
                else -> j + run
            }
        }
        return null
    }

    private fun closes(s: String, at: Int, run: Int, marker: Char, from: Int): Boolean =
        at > from && !s[at - 1].isWhitespace() &&
            (marker != '_' || s.getOrNull(at + run)?.isLetterOrDigit() != true)

    private fun link(s: String, i: Int): Int? {
        val labelEnd = closingBracket(s, i) ?: return null
        if (s.getOrNull(labelEnd + 1) != '(') return null
        val urlEnd = closingParenthesis(s, labelEnd + 2) ?: return null
        val url = s.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ').removeSurrounding("<", ">")
        if (url.isEmpty()) return null
        val label = s.substring(i + 1, labelEnd)
        if (LINK_SCHEMES.none { url.startsWith(it, ignoreCase = true) }) {
            write(label)
        } else {
            out.withLink(LinkAnnotation.Url(url, TextLinkStyles(styles.link))) { write(label) }
        }
        return urlEnd + 1
    }

    private fun bareUrl(s: String, i: Int): Int? {
        val scheme = LINK_SCHEMES.take(2).firstOrNull { s.startsWith(it, i) } ?: return null
        if (i > 0 && s[i - 1].isLetterOrDigit()) return null
        var end = i
        while (end < s.length && !s[end].isWhitespace() && s[end] != '<') end++
        while (end > i + scheme.length && trailsUrl(s.substring(i, end))) end--
        if (end == i + scheme.length) return null
        val url = s.substring(i, end)
        out.withLink(LinkAnnotation.Url(url, TextLinkStyles(styles.link))) { append(url) }
        return end
    }

    private fun trailsUrl(url: String): Boolean {
        val last = url.last()
        return last in URL_TRAILING_PUNCTUATION || (last == ')' && url.count { it == ')' } > url.count { it == '(' })
    }
}

private fun runLength(s: String, from: Int, char: Char): Int {
    var end = from
    while (end < s.length && s[end] == char) end++
    return end - from
}

/** The index where the code span opened at [open] closes: the next backtick run of exactly the same length. */
private fun codeSpanEnd(s: String, open: Int): Int? {
    val length = runLength(s, open, '`')
    var j = open + length
    while (j < s.length) {
        if (s[j] != '`') {
            j++
            continue
        }
        val run = runLength(s, j, '`')
        if (run == length) return j
        j += run
    }
    return null
}

private fun closingBracket(s: String, open: Int): Int? {
    var depth = 0
    var j = open
    while (j < s.length) {
        when (s[j]) {
            '\\' -> j++
            '[' -> depth++
            ']' -> if (--depth == 0) return j
        }
        j++
    }
    return null
}

private fun closingParenthesis(s: String, from: Int): Int? {
    var depth = 1
    for (j in from until s.length) {
        when (s[j]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return j
        }
    }
    return null
}
