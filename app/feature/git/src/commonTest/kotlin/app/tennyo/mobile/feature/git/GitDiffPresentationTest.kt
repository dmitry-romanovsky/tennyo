package app.tennyo.mobile.feature.git


import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GitDiffPresentationTest {
    @Test
    fun `unified rows reserve a marker and preserve both line-number columns`() {
        val content = GitDiffPresentation.Content(
            lines = listOf(
                GitDiffPresentation.Line("same", "same", 1, 1),
                GitDiffPresentation.Line("old", null, 2, null),
                GitDiffPresentation.Line(null, "new", null, 2),
            ),
            addedLines = 1,
            deletedLines = 1,
        )

        val rows = content.unifiedRows()

        assertEquals(listOf(" ", "−", "+"), rows.map { it.marker })
        assertEquals(listOf(1, 2, null), rows.map { it.oldLineNumber })
        assertEquals(listOf(1, null, 2), rows.map { it.newLineNumber })
        assertEquals(listOf("same", "old", "new"), rows.map { it.text })
    }

    @Test
    fun `renders aligned before and after lines`() {
        val presentation = GitDiff(
            repositoryId = "repo",
            changeId = "change",
            beforeContent = "one\ntwo",
            afterContent = "one\nthree\nfour",
        ).toPresentation()

        val content = assertIs<GitDiffPresentation.Content>(presentation)
        assertEquals(3, content.lines.size)
        assertEquals("two", content.lines[1].before)
        assertEquals("three", content.lines[1].after)
        assertNull(content.lines[2].beforeNumber)
        assertEquals(3, content.lines[2].afterNumber)
    }

    @Test
    fun `empty before side produces only added rows`() {
        val content = assertIs<GitDiffPresentation.Content>(
            GitDiff("repo", "change", beforeContent = "", afterContent = "one\ntwo").toPresentation()
        )

        assertEquals(listOf("+", "+"), content.unifiedRows().map { it.marker })
        assertEquals(listOf(null, null), content.unifiedRows().map { it.oldLineNumber })
        assertEquals(listOf(1, 2), content.unifiedRows().map { it.newLineNumber })
    }

    @Test
    fun `unchanged lines beyond the context fold into one row with a stable key`() {
        val rows = contentWith(changedAt = setOf(10), size = 21).unifiedRows()

        val folds = rows.filter { it.foldKey != null }
        assertEquals(listOf(0, 14), folds.map { it.foldKey })
        assertEquals(listOf(7, 7), folds.map { it.foldedLines })
        assertEquals(10, rows.size)
    }

    @Test
    fun `an expanded fold shows its lines instead`() {
        val rows = contentWith(changedAt = setOf(10), size = 21).unifiedRows(expandedFolds = setOf(0))

        assertEquals(listOf(14), rows.mapNotNull { it.foldKey })
        assertEquals(1, rows.first().oldLineNumber)
    }

    @Test
    fun `a run of one unchanged line is shown rather than folded`() {
        val rows = contentWith(changedAt = setOf(0, 8), size = 9).unifiedRows()

        assertEquals(emptyList(), rows.mapNotNull { it.foldKey })
    }

    @Test
    fun `separate edit groups close enough to share context stay unfolded between them`() {
        val rows = contentWith(changedAt = setOf(0, 7), size = 8).unifiedRows()

        assertEquals(emptyList(), rows.mapNotNull { it.foldKey })
    }

    @Test
    fun `a file with no changed lines folds into one row`() {
        val rows = contentWith(changedAt = emptySet(), size = 40).unifiedRows()

        assertEquals(40, rows.single().foldedLines)
        assertEquals(0, rows.single().foldKey)
    }

    @Test
    fun `many separate edit groups fold the gaps between them`() {
        val groupCount = 5_000
        val lines = buildList {
            repeat(groupCount) { index ->
                val lineNumber = index * 20 + 1
                add(GitDiffPresentation.Line("old-$index", "new-$index", lineNumber, lineNumber))
                repeat(19) { gap ->
                    add(GitDiffPresentation.Line("same", "same", lineNumber + gap + 1, lineNumber + gap + 1))
                }
            }
        }

        val rows = GitDiffPresentation.Content(lines, groupCount, groupCount).unifiedRows()

        assertEquals(groupCount - 1, rows.count { it.foldKey != null && it.foldedLines == 13 })
    }

    @Test
    fun `long lines remain intact for horizontal scrolling`() {
        val longLine = "column".repeat(2_000)
        val content = assertIs<GitDiffPresentation.Content>(
            GitDiff("repo", "change", beforeContent = longLine, afterContent = longLine).toPresentation()
        )

        assertEquals(longLine, content.unifiedRows().single().text)
        assertEquals(" ", content.unifiedRows().single().marker)
    }

    @Test
    fun `large line numbers remain in their respective gutter columns`() {
        val rows = GitDiffPresentation.Content(
            lines = listOf(GitDiffPresentation.Line("same", "same", 999_999, 1_000_000)),
            addedLines = 0,
            deletedLines = 0,
        ).unifiedRows()

        assertEquals(999_999, rows.single().oldLineNumber)
        assertEquals(1_000_000, rows.single().newLineNumber)
        assertEquals(" ", rows.single().marker)
    }

    @Test
    fun `insertion does not turn following unchanged lines into replacements`() {
        val presentation = GitDiff(
            repositoryId = "repo",
            changeId = "change",
            beforeContent = "one\ntwo\nthree",
            afterContent = "zero\none\ntwo\nthree",
        ).toPresentation()

        val content = assertIs<GitDiffPresentation.Content>(presentation)
        assertEquals(4, content.lines.size)
        assertNull(content.lines[0].before)
        assertEquals("zero", content.lines[0].after)
        assertEquals("one", content.lines[1].before)
        assertEquals("one", content.lines[1].after)
        assertEquals(1, content.addedLines)
        assertEquals(0, content.deletedLines)
    }

    @Test
    fun `rejects diff before allocating line search when character budget is exceeded`() {
        val presentation = GitDiff(
            repositoryId = "repo",
            changeId = "change",
            beforeContent = "a".repeat(MAX_MOBILE_DIFF_TOTAL_CHARS + 1),
            afterContent = "",
        ).toPresentation()

        assertIs<GitDiffPresentation.TooLarge>(presentation)
    }

    @Test
    fun `reports complexity budget exhaustion without partial rows`() {
        val presentation = GitDiff(
            repositoryId = "repo",
            changeId = "change",
            beforeContent = (0 until 20).joinToString("\n") { "before-$it" },
            afterContent = (0 until 20).joinToString("\n") { "after-$it" },
        ).toPresentation(maxSearchSteps = 10)

        assertIs<GitDiffPresentation.TooComplex>(presentation)
    }

    @Test
    fun `does not attempt to render binary or unavailable content`() {
        assertIs<GitDiffPresentation.Binary>(
            GitDiff(
                "repo",
                "change",
                isBinary = true
            ).toPresentation()
        )
        assertIs<GitDiffPresentation.Unavailable>(
            GitDiff(
                "repo",
                "change",
                unavailableReason = "Not available"
            ).toPresentation()
        )
    }

    @Test
    fun `propagates cancellation during diff search`() {
        assertFailsWith<CancellationException> {
            GitDiff(
                repositoryId = "repo",
                changeId = "change",
                beforeContent = (0 until 20).joinToString("\n") { "before-$it" },
                afterContent = (0 until 20).joinToString("\n") { "after-$it" },
            ).toPresentation(cancellationCheck = { throw CancellationException("cancelled") })
        }
    }

    private fun contentWith(changedAt: Set<Int>, size: Int) = GitDiffPresentation.Content(
        lines = List(size) { index ->
            if (index in changedAt) {
                GitDiffPresentation.Line("old", "new", index + 1, index + 1)
            } else {
                GitDiffPresentation.Line("same", "same", index + 1, index + 1)
            }
        },
        addedLines = changedAt.size,
        deletedLines = changedAt.size,
    )
}
