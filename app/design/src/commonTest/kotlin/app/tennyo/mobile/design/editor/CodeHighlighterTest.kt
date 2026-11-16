package app.tennyo.mobile.design.editor

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeHighlighterTest {

    @Test
    fun detectsLanguageByExtensionContentTypeAndContent() {
        assertEquals(EditorLanguage.KOTLIN, detectEditorLanguage("Main.kt"))
        assertEquals(EditorLanguage.JAVA, detectEditorLanguage("Main.java"))
        assertEquals(EditorLanguage.JAVASCRIPT, detectEditorLanguage("index.jsx"))
        assertEquals(EditorLanguage.TYPESCRIPT, detectEditorLanguage("client.tsx"))
        assertEquals(EditorLanguage.HTML, detectEditorLanguage("index.html"))
        assertEquals(EditorLanguage.XML, detectEditorLanguage("layout.xml"))
        assertEquals(EditorLanguage.JSON, detectEditorLanguage("package.json"))
        assertEquals(EditorLanguage.MARKDOWN, detectEditorLanguage("README.md"))
        assertEquals(EditorLanguage.PROPERTIES, detectEditorLanguage("gradle.properties"))

        assertEquals(EditorLanguage.JSON, detectEditorLanguage("payload", contentType = "application/json"))
        assertEquals(EditorLanguage.HTML, detectEditorLanguage("unknown", textSample = "<!doctype html><html></html>"))
        assertEquals(EditorLanguage.XML, detectEditorLanguage("unknown", textSample = "<?xml version=\"1.0\"?>"))
        assertEquals(EditorLanguage.PLAIN, detectEditorLanguage("LICENSE"))
    }

    @Test
    fun highlightsCommonLanguageTokensWithoutChangingText(): Unit = runBlocking {
        val result = CodeHighlighter().highlight(
            HighlightRequest(
                fileId = "Main.kt",
                revision = 1,
                text = "fun main() {\n  val answer = 42\n  println(\"hi\")\n}",
                language = EditorLanguage.KOTLIN,
            )
        )

        assertEquals(4, result.totalLineCount)
        assertEquals(4, result.highlightedLineCount)
        assertFalse(result.truncated)
        assertEquals("fun main() {", result.lines.first().text)
        assertTrue(result.lines.first().spans.any { it.style == HighlightStyle.KEYWORD })
        assertTrue(result.lines.first().spans.any { it.style == HighlightStyle.FUNCTION })
        assertTrue(result.lines[1].spans.any { it.style == HighlightStyle.NUMBER })
        assertTrue(result.lines[2].spans.any { it.style == HighlightStyle.STRING })
    }

    @Test
    fun pythonIsDetectedAndHighlighted(): Unit = runBlocking {
        assertEquals(EditorLanguage.PYTHON, detectEditorLanguage("main.py"))
        assertEquals(EditorLanguage.PYTHON, detectEditorLanguage("run", textSample = "#!/usr/bin/env python3\nprint(1)"))

        val result = CodeHighlighter().highlight(
            HighlightRequest(
                fileId = "main.py",
                revision = 1,
                text = "@dataclass\ndef greet(name):  # say hi\n    return f\"Hi {name}\"",
                language = EditorLanguage.PYTHON,
            )
        )

        assertTrue(result.lines[0].spans.any { it.style == HighlightStyle.TYPE })
        assertTrue(result.lines[1].spans.any { it.style == HighlightStyle.KEYWORD })
        assertTrue(result.lines[1].spans.any { it.style == HighlightStyle.FUNCTION })
        assertTrue(result.lines[1].spans.any { it.style == HighlightStyle.COMMENT })
        assertFalse(result.lines[1].spans.any { it.style == HighlightStyle.COMMENT && it.start < 10 })
        assertTrue(result.lines[2].spans.any { it.style == HighlightStyle.STRING })
    }

    @Test
    fun stringsAndCommentsAreNotHighlightedInside(): Unit = runBlocking {
        val lines = highlight(EditorLanguage.KOTLIN, "val s = \"if x // not a comment\" // real")

        val spans = lines[0].spans
        val string = spans.single { it.style == HighlightStyle.STRING }
        assertTrue(spans.none { it.style == HighlightStyle.KEYWORD && it.start > string.start && it.end <= string.end })
        assertEquals(1, spans.count { it.style == HighlightStyle.COMMENT })
        assertTrue(spans.single { it.style == HighlightStyle.COMMENT }.start > string.end)
    }

    @Test
    fun blockCommentsAndDocstringsSpanLines(): Unit = runBlocking {
        val kotlin = highlight(EditorLanguage.KOTLIN, "/* start\nval inside = 1\nend */ val after = 2")
        assertEquals(listOf(HighlightStyle.COMMENT), kotlin[1].spans.map { it.style })
        assertTrue(kotlin[2].spans.any { it.style == HighlightStyle.KEYWORD })

        val python = highlight(EditorLanguage.PYTHON, "def f():\n    \"\"\"Docs\n    if not code\n    \"\"\"\n    return 1")
        assertEquals(listOf(HighlightStyle.STRING), python[2].spans.map { it.style })
        assertTrue(python[4].spans.any { it.style == HighlightStyle.KEYWORD })
    }

    @Test
    fun pythonMarksBasicErrors(): Unit = runBlocking {
        suspend fun errorLines(text: String) = highlight(EditorLanguage.PYTHON, text)
            .filter { line -> line.spans.any { it.style == HighlightStyle.ERROR } }
            .map { it.index }

        assertEquals(listOf(0), errorLines("def f(x)\n    return x"))
        assertEquals(listOf(0), errorLines("print(1))"))
        assertEquals(listOf(0), errorLines("items = [1, 2\n"))
        assertEquals(listOf(0), errorLines("print(\"hi)"))
        assertEquals(emptyList(), errorLines("total = add(\n    1,\n    2,\n)\nif (a and\n        b):\n    pass"))
        assertEquals(emptyList(), errorLines("text = \"(\"  # ) in a comment\nclass A: pass"))
    }

    private suspend fun highlight(language: EditorLanguage, text: String): List<HighlightedLine> =
        CodeHighlighter().highlight(HighlightRequest("f", 1, text, language)).lines

    @Test
    fun unchangedLinesKeepStableSpansAcrossRevisions(): Unit = runBlocking {
        val highlighter = CodeHighlighter()
        val first = highlighter.highlight(
            HighlightRequest(
                fileId = "Main.kt",
                revision = 1,
                text = "fun main() {\n  val name = \"one\"\n}",
                language = EditorLanguage.KOTLIN,
            )
        )
        val second = highlighter.highlight(
            HighlightRequest(
                fileId = "Main.kt",
                revision = 2,
                text = "fun main() {\n  val name = \"two\"\n}",
                language = EditorLanguage.KOTLIN,
            )
        )

        assertEquals(first.lines[0].text, second.lines[0].text)
        assertEquals(first.lines[0].styleSignature, second.lines[0].styleSignature)
        assertEquals(first.lines[0].spans, second.lines[0].spans)
    }

    @Test
    fun capsLargeFiles(): Unit = runBlocking {
        val result = CodeHighlighter().highlight(
            HighlightRequest(
                fileId = "large.json",
                revision = 1,
                text = (1..10).joinToString("\n") { "\"key$it\": $it" },
                language = EditorLanguage.JSON,
                maxHighlightedLines = 3,
            )
        )

        assertEquals(10, result.totalLineCount)
        assertEquals(3, result.highlightedLineCount)
        assertTrue(result.truncated)
        assertEquals(listOf(0, 1, 2), result.lines.map { it.index })
        assertTrue(result.lines.first().spans.any { it.style == HighlightStyle.KEY })
    }

    @Test
    fun staleResultsAreRejectedByRevisionFileAndLanguage() {
        val result = HighlightResult(
            fileId = "Main.kt",
            revision = 3,
            language = EditorLanguage.KOTLIN,
            textLength = 12,
            totalLineCount = 1,
            highlightedLineCount = 1,
            truncated = false,
            lines = emptyList(),
        )

        assertTrue(result.isFreshFor("Main.kt", revision = 3, language = EditorLanguage.KOTLIN))
        assertFalse(result.isFreshFor("Main.kt", revision = 4, language = EditorLanguage.KOTLIN))
        assertFalse(result.isFreshFor("Other.kt", revision = 3, language = EditorLanguage.KOTLIN))
        assertFalse(result.isFreshFor("Main.kt", revision = 3, language = EditorLanguage.JAVA))
    }

    @Test
    fun schedulesHighlightAfterIdleDelay() {
        val schedule = EditorHighlightSchedule()

        assertEquals(
            250L,
            schedule.delayMillis(
                pendingSinceMillis = 0L,
                lastChangeMillis = 750L,
                nowMillis = 1_500L,
            )
        )
        assertEquals(
            0L,
            schedule.delayMillis(
                pendingSinceMillis = 0L,
                lastChangeMillis = 750L,
                nowMillis = 1_750L,
            )
        )
    }

    @Test
    fun forcesHighlightDuringContinuousTyping() {
        val schedule = EditorHighlightSchedule()

        assertEquals(
            100L,
            schedule.delayMillis(
                pendingSinceMillis = 1_000L,
                lastChangeMillis = 5_800L,
                nowMillis = 5_900L,
            )
        )
        assertEquals(
            0L,
            schedule.delayMillis(
                pendingSinceMillis = 1_000L,
                lastChangeMillis = 5_900L,
                nowMillis = 6_000L,
            )
        )
    }

    @Test
    fun usesCustomHighlightTimingValues() {
        val schedule = EditorHighlightSchedule(
            EditorHighlightTiming(
                settleDelayMillis = 200L,
                maxContinuousDelayMillis = 750L,
            )
        )

        assertEquals(
            50L,
            schedule.delayMillis(
                pendingSinceMillis = 1_000L,
                lastChangeMillis = 1_300L,
                nowMillis = 1_450L,
            )
        )
        assertEquals(
            0L,
            schedule.delayMillis(
                pendingSinceMillis = 1_000L,
                lastChangeMillis = 1_700L,
                nowMillis = 1_750L,
            )
        )
    }

    @Test
    fun cachedHighlightOnlyKeepsLinesWithMatchingText() {
        val result = HighlightResult(
            fileId = "Main.kt",
            revision = 1,
            language = EditorLanguage.KOTLIN,
            textLength = 38,
            totalLineCount = 3,
            highlightedLineCount = 3,
            truncated = false,
            lines = listOf(
                HighlightedLine(0, "fun main() {", listOf(HighlightSpan(0, 3, HighlightStyle.KEYWORD))),
                HighlightedLine(1, "  val name = \"one\"", listOf(HighlightSpan(2, 5, HighlightStyle.KEYWORD))),
                HighlightedLine(2, "}", emptyList()),
            ),
        )

        val compatible = result.compatibleLinesFor(
            fileId = "Main.kt",
            language = EditorLanguage.KOTLIN,
            text = "fun main() {\n  val name = \"two\"\n}",
        )

        assertEquals(listOf(0, 2), compatible.map { it.index })
        assertEquals("fun main() {", compatible.first().text)
    }

    @Test
    fun cachedHighlightFollowsLinesShiftedByAnInsertedLine() {
        val result = HighlightResult(
            fileId = "Main.kt",
            revision = 1,
            language = EditorLanguage.KOTLIN,
            textLength = 30,
            totalLineCount = 3,
            highlightedLineCount = 3,
            truncated = false,
            lines = listOf(
                HighlightedLine(0, "fun main() {", listOf(HighlightSpan(0, 3, HighlightStyle.KEYWORD))),
                HighlightedLine(1, "  val one = 1", listOf(HighlightSpan(2, 5, HighlightStyle.KEYWORD))),
                HighlightedLine(2, "}", emptyList()),
            ),
        )

        val compatible = result.compatibleLinesFor(
            fileId = "Main.kt",
            language = EditorLanguage.KOTLIN,
            text = "fun main() {\n  \n  val one = 1\n}",
        )

        assertEquals(listOf(0, 2, 3), compatible.map { it.index })
        assertEquals("  val one = 1", compatible[1].text)
    }

    @Test
    fun grammarLookupCoversConfiguredLanguages() {
        val grammars = EditorLanguage.entries
            .filterNot { it == EditorLanguage.PLAIN }
            .associateWith { TextMateLiteGrammarRegistry.grammarFor(it) }

        assertTrue(grammars.all { (_, grammar) -> grammar != null })
        assertEquals("source.kotlin", grammars.getValue(EditorLanguage.KOTLIN)?.scopeName)
        assertEquals("source.java", grammars.getValue(EditorLanguage.JAVA)?.scopeName)
        assertEquals("source.js", grammars.getValue(EditorLanguage.JAVASCRIPT)?.scopeName)
        assertEquals("source.ts", grammars.getValue(EditorLanguage.TYPESCRIPT)?.scopeName)
        assertEquals("source.json", grammars.getValue(EditorLanguage.JSON)?.scopeName)
        assertEquals("text.html.basic", grammars.getValue(EditorLanguage.HTML)?.scopeName)
        assertEquals("text.xml", grammars.getValue(EditorLanguage.XML)?.scopeName)
        assertEquals("text.html.markdown", grammars.getValue(EditorLanguage.MARKDOWN)?.scopeName)
        assertEquals("source.properties", grammars.getValue(EditorLanguage.PROPERTIES)?.scopeName)
    }
}
