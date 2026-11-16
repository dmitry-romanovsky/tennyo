package app.tennyo.mobile.design.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

data class HighlightRequest(
  val fileId: String,
  val revision: Long,
  val text: String,
  val language: EditorLanguage,
  val maxHighlightedLines: Int = 5_000,
)

data class HighlightResult(
  val fileId: String,
  val revision: Long,
  val language: EditorLanguage,
  val textLength: Int,
  val totalLineCount: Int,
  val highlightedLineCount: Int,
  val truncated: Boolean,
  val lines: List<HighlightedLine>,
) {
  fun isFreshFor(fileId: String, revision: Long, language: EditorLanguage): Boolean =
    this.fileId == fileId && this.revision == revision && this.language == language

  fun compatibleLinesFor(fileId: String, language: EditorLanguage, text: String): List<HighlightedLine> {
    if (this.fileId != fileId || this.language != language) return emptyList()
    val currentLines = text.split('\n')
    // A line inserted or removed shifts everything below it; counting from the end still finds those lines.
    val shift = totalLineCount - currentLines.size
    return currentLines.mapIndexedNotNull { index, line ->
      (lines.getOrNull(index)?.takeIf { it.text == line } ?: lines.getOrNull(index + shift)?.takeIf { it.text == line })
        ?.copy(index = index)
    }
  }
}

data class HighlightedLine(
  val index: Int,
  val text: String,
  val spans: List<HighlightSpan>,
) {
  val styleSignature: String by lazy {
    spans.joinToString(separator = "|") { span -> "${span.start}:${span.end}:${span.style}" }
  }
}

data class HighlightSpan(
  val start: Int,
  val end: Int,
  val style: HighlightStyle,
)

enum class HighlightStyle {
  KEYWORD,
  FUNCTION,
  NUMBER,
  STRING,
  KEY,
  COMMENT,
  TYPE,
  PROPERTY,

  /** A likely mistake — an unclosed bracket, a missing colon — drawn over whatever colour the text has. */
  ERROR,
}

data class EditorHighlightTiming(
  val settleDelayMillis: Long = 1_000L,
  val maxContinuousDelayMillis: Long = 5_000L,
)

class EditorHighlightSchedule(
  private val timing: EditorHighlightTiming = EditorHighlightTiming(),
) {
  fun delayMillis(pendingSinceMillis: Long, lastChangeMillis: Long, nowMillis: Long): Long {
    val idleRefreshAt = lastChangeMillis + timing.settleDelayMillis.coerceAtLeast(0L)
    val forcedRefreshAt = pendingSinceMillis + timing.maxContinuousDelayMillis.coerceAtLeast(0L)
    return (min(idleRefreshAt, forcedRefreshAt) - nowMillis).coerceAtLeast(0L)
  }
}

/** Highlights editor text in a background-friendly, line-oriented form that can be cached by callers. */
class CodeHighlighter {
  suspend fun highlight(request: HighlightRequest): HighlightResult = withContext(Dispatchers.Default) {
    val sourceLines = request.text.split('\n')
    val grammar = TextMateLiteGrammarRegistry.grammarFor(request.language)
    val visible = sourceLines.take(request.maxHighlightedLines.coerceAtLeast(0))
    // Lines are tokenized in order: a block comment or docstring left open on one line colours the next.
    var openBlock: BlockRule? = null
    val tokens = visible.map { line ->
      val (spans, stillOpen) = grammar?.highlight(line, openBlock) ?: (emptyList<HighlightSpan>() to null)
      openBlock = stillOpen
      spans
    }
    val errors = if (request.language == EditorLanguage.PYTHON) pythonErrors(visible, tokens) else emptyMap()
    val highlightedLines = visible.mapIndexed { index, line ->
      HighlightedLine(index = index, text = line, spans = tokens[index] + errors[index].orEmpty())
    }
    HighlightResult(
      fileId = request.fileId,
      revision = request.revision,
      language = request.language,
      textLength = request.text.length,
      totalLineCount = sourceLines.size,
      highlightedLineCount = highlightedLines.size,
      truncated = sourceLines.size > highlightedLines.size,
      lines = highlightedLines,
    )
  }
}

/** A construct that can run over several lines: a block comment, a triple-quoted string. */
data class BlockRule(val open: String, val close: String, val style: HighlightStyle)

data class TextMateLiteGrammar(
  val scopeName: String,
  val language: EditorLanguage,
  private val patterns: List<TextMateLitePattern>,
  private val blocks: List<BlockRule> = emptyList(),
) {
  /**
   * The spans of [line] when [openBlock] is still open from the line before, and the block this line leaves open.
   *
   * Every pattern proposes spans and the earliest wins, the longest on a tie, so nothing is coloured inside a string
   * or a comment that started before it: `"if"` is a string, and `"http://x"` holds no comment.
   */
  fun highlight(line: String, openBlock: BlockRule?): Pair<List<HighlightSpan>, BlockRule?> {
    val kept = mutableListOf<HighlightSpan>()
    var from = 0
    if (openBlock != null) {
      val close = line.indexOf(openBlock.close)
      if (close < 0) return listOf(HighlightSpan(0, line.length, openBlock.style)) to openBlock
      from = close + openBlock.close.length
      kept += HighlightSpan(0, from, openBlock.style)
    }
    val candidates = patterns.flatMap { it.findSpans(line) }.filter { it.start >= from }.map { Candidate(it, null) } +
      blockCandidates(line, from)
    var cursor = from
    var leftOpen: BlockRule? = null
    candidates
      .sortedWith(compareBy<Candidate> { it.span.start }.thenByDescending { it.span.end })
      .forEach { candidate ->
        if (candidate.span.start < cursor || candidate.span.start == candidate.span.end) return@forEach
        kept += candidate.span
        cursor = candidate.span.end
        leftOpen = candidate.unterminated
      }
    return kept to leftOpen
  }

  /** [line] on its own, as a diff shows it: nothing carries in from lines around it. */
  fun highlight(line: String): List<HighlightSpan> = highlight(line, openBlock = null).first

  private fun blockCandidates(line: String, from: Int): List<Candidate> = blocks.flatMap { rule ->
    line.indicesOf(rule.open, from).map { start ->
      val close = line.indexOf(rule.close, start + rule.open.length)
      if (close < 0) {
        Candidate(HighlightSpan(start, line.length, rule.style), unterminated = rule)
      } else {
        Candidate(HighlightSpan(start, close + rule.close.length, rule.style), unterminated = null)
      }
    }.toList()
  }

  private class Candidate(val span: HighlightSpan, val unterminated: BlockRule?)
}

sealed interface TextMateLitePattern {
  fun findSpans(line: String): List<HighlightSpan>
}

/**
 * A comment from every [prefix] to the end of the line. Every occurrence is proposed, not only the first: the first
 * may sit inside a string (`"http://x" // real`), and then the overlap rule needs the next one to fall back to.
 */
data class LineCommentPattern(private val prefix: String) : TextMateLitePattern {
  override fun findSpans(line: String): List<HighlightSpan> =
    line.indicesOf(prefix, from = 0).map { HighlightSpan(it, line.length, HighlightStyle.COMMENT) }
      .toList()
}

data class RegexTextMateLitePattern(
  private val regex: Regex,
  private val style: HighlightStyle,
) : TextMateLitePattern {
  override fun findSpans(line: String): List<HighlightSpan> =
    regex.findAll(line).map { match -> match.toSpan(style) }.toList()
}

data class KeywordTextMateLitePattern(
  private val keywords: Set<String>,
) : TextMateLitePattern {
  override fun findSpans(line: String): List<HighlightSpan> =
    TextMateLiteRegex.word.findAll(line).mapNotNull { match ->
      val word = match.value
      when {
        word in keywords -> match.toSpan(HighlightStyle.KEYWORD)
        word.firstOrNull()?.isUpperCase() == true -> match.toSpan(HighlightStyle.TYPE)
        line.getOrNull(match.range.last + 1) == '(' -> match.toSpan(HighlightStyle.FUNCTION)
        else -> null
      }
    }.toList()
}

object TextMateLiteGrammarRegistry {
  private val javascriptKeywords = setOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
    "do", "else", "export", "extends", "finally", "for", "from", "function", "if", "import", "in", "instanceof",
    "let", "new", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "yield",
  )

  private val grammars = listOf(
    TextMateLiteGrammar(
      scopeName = "source.kotlin",
      language = EditorLanguage.KOTLIN,
      patterns = codePatterns(
        keywords = setOf(
          "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
          "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
          "typeof", "val", "var", "when", "while",
        )
      ),
      blocks = listOf(BlockRule("/*", "*/", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "source.java",
      language = EditorLanguage.JAVA,
      patterns = codePatterns(
        keywords = setOf(
          "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
          "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
          "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
          "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
          "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
        )
      ),
      blocks = listOf(BlockRule("/*", "*/", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "source.js",
      language = EditorLanguage.JAVASCRIPT,
      patterns = codePatterns(keywords = javascriptKeywords),
      blocks = listOf(BlockRule("/*", "*/", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "source.ts",
      language = EditorLanguage.TYPESCRIPT,
      patterns = codePatterns(keywords = javascriptKeywords),
      blocks = listOf(BlockRule("/*", "*/", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "source.json",
      language = EditorLanguage.JSON,
      patterns = listOf(
        RegexTextMateLitePattern(TextMateLiteRegex.jsonKey, HighlightStyle.KEY),
        RegexTextMateLitePattern(TextMateLiteRegex.string, HighlightStyle.STRING),
        RegexTextMateLitePattern(TextMateLiteRegex.number, HighlightStyle.NUMBER),
      ),
    ),
    TextMateLiteGrammar(
      scopeName = "text.html.basic",
      language = EditorLanguage.HTML,
      patterns = xmlPatterns(),
      blocks = listOf(BlockRule("<!--", "-->", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "text.xml",
      language = EditorLanguage.XML,
      patterns = xmlPatterns(),
      blocks = listOf(BlockRule("<!--", "-->", HighlightStyle.COMMENT)),
    ),
    TextMateLiteGrammar(
      scopeName = "text.html.markdown",
      language = EditorLanguage.MARKDOWN,
      patterns = listOf(
        RegexTextMateLitePattern(TextMateLiteRegex.markdownHeading, HighlightStyle.KEYWORD),
        RegexTextMateLitePattern(TextMateLiteRegex.markdownCode, HighlightStyle.STRING),
      ),
    ),
    TextMateLiteGrammar(
      scopeName = "source.python",
      language = EditorLanguage.PYTHON,
      patterns = listOf(
        KeywordTextMateLitePattern(
          setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
            "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "match", "case", "nonlocal", "not", "or", "pass", "raise", "return", "self", "try", "while",
            "with", "yield",
          )
        ),
        RegexTextMateLitePattern(TextMateLiteRegex.pythonDecorator, HighlightStyle.TYPE),
        RegexTextMateLitePattern(TextMateLiteRegex.string, HighlightStyle.STRING),
        RegexTextMateLitePattern(TextMateLiteRegex.number, HighlightStyle.NUMBER),
        LineCommentPattern("#"),
      ),
      blocks = listOf(
        BlockRule("\"\"\"", "\"\"\"", HighlightStyle.STRING),
        BlockRule("'''", "'''", HighlightStyle.STRING),
      ),
    ),
    TextMateLiteGrammar(
      scopeName = "source.properties",
      language = EditorLanguage.PROPERTIES,
      patterns = listOf(RegexTextMateLitePattern(TextMateLiteRegex.propertyKey, HighlightStyle.PROPERTY)),
    ),
  ).associateBy { grammar -> grammar.language }

  fun grammarFor(language: EditorLanguage): TextMateLiteGrammar? = grammars[language]

  private fun codePatterns(keywords: Set<String>): List<TextMateLitePattern> =
    listOf(
      KeywordTextMateLitePattern(keywords),
      RegexTextMateLitePattern(TextMateLiteRegex.string, HighlightStyle.STRING),
      RegexTextMateLitePattern(TextMateLiteRegex.number, HighlightStyle.NUMBER),
      LineCommentPattern("//"),
    )

  private fun xmlPatterns(): List<TextMateLitePattern> =
    listOf(
      RegexTextMateLitePattern(TextMateLiteRegex.xmlTag, HighlightStyle.KEYWORD),
      RegexTextMateLitePattern(TextMateLiteRegex.string, HighlightStyle.STRING),
    )

}

private object TextMateLiteRegex {
  val word = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
  val string = Regex("\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'")
  val number = Regex("\\b\\d+(?:\\.\\d+)?\\b")
  val pythonDecorator = Regex("^\\s*@[A-Za-z_][A-Za-z0-9_.]*")
  val jsonKey = Regex("\"(?:\\\\.|[^\"])+\"(?=\\s*:)")
  val xmlTag = Regex("</?[A-Za-z_][A-Za-z0-9_.:-]*")
  val markdownHeading = Regex("^#+(?=\\s|$)")
  val markdownCode = Regex("`[^`]+`")
  val propertyKey = Regex("^[^#!\\s:=][^:=]*(?=\\s*[:=])")
}

/** Every index at or after [from] where [needle] starts. */
private fun String.indicesOf(needle: String, from: Int): Sequence<Int> =
  generateSequence(indexOf(needle, from).takeIf { it >= 0 }) { indexOf(needle, it + 1).takeIf { next -> next >= 0 } }

private fun MatchResult.toSpan(style: HighlightStyle): HighlightSpan =
  HighlightSpan(range.first, range.last + 1, style)
