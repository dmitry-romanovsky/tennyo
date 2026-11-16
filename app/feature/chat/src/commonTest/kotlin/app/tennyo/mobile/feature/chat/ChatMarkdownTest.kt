package app.tennyo.mobile.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.tennyo.mobile.feature.chat.MarkdownBlock.CodeFence
import app.tennyo.mobile.feature.chat.MarkdownBlock.Heading
import app.tennyo.mobile.feature.chat.MarkdownBlock.ListBlock
import app.tennyo.mobile.feature.chat.MarkdownBlock.Paragraph
import app.tennyo.mobile.feature.chat.MarkdownBlock.Quote
import app.tennyo.mobile.feature.chat.MarkdownBlock.Rule
import app.tennyo.mobile.feature.chat.MarkdownBlock.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What an agent's Markdown becomes: blocks first, then the inline formatting inside their text. */
class ChatMarkdownTest {
    private val styles = InlineStyles(
        link = SpanStyle(fontWeight = FontWeight.Light),
        code = SpanStyle(fontFamily = FontFamily.Monospace),
    )

    private fun inline(text: String): AnnotatedString = inlineMarkdown(text, styles)

    @Test
    fun `headings carry their level`() {
        assertEquals(
            listOf(Heading(1, "Title"), Heading(3, "Deep"), Paragraph("#hashtag")),
            parseMarkdown("# Title\n\n### Deep ###\n#hashtag"),
        )
    }

    @Test
    fun `consecutive lines join into one paragraph and hard breaks stay`() {
        assertEquals(
            listOf(Paragraph("one two"), Paragraph("a\nb\nc")),
            parseMarkdown("one\ntwo\n\na  \nb\\\nc"),
        )
    }

    @Test
    fun `lists nest by indentation and number their items`() {
        val blocks = parseMarkdown("- a\n  - b\n    - c\n- d\n\n1. x\n2) y")
        assertEquals(
            listOf(
                ListBlock(
                    ordered = false,
                    items = listOf(
                        ListItem("a", 0, null, null),
                        ListItem("b", 1, null, null),
                        ListItem("c", 2, null, null),
                        ListItem("d", 0, null, null),
                    ),
                ),
                ListBlock(true, listOf(ListItem("x", 0, 1, null), ListItem("y", 0, 2, null))),
            ),
            blocks,
        )
    }

    @Test
    fun `task items report whether they are checked`() {
        val items = (parseMarkdown("- [ ] todo\n* [x] done\n+ plain").single() as ListBlock).items
        assertEquals(listOf(false, true, null), items.map { it.checked })
        assertEquals(listOf("todo", "done", "plain"), items.map { it.text })
    }

    @Test
    fun `a list item continues on its indented line`() {
        val list = parseMarkdown("- first\n  more\n- second").single() as ListBlock
        assertEquals(listOf("first more", "second"), list.items.map { it.text })
    }

    @Test
    fun `fenced code keeps its language and lines`() {
        assertEquals(
            listOf(CodeFence("kotlin", listOf("val a = 1", "", "  val b = 2")), Paragraph("after")),
            parseMarkdown("```kotlin\nval a = 1\n\n  val b = 2\n```\nafter"),
        )
    }

    @Test
    fun `an unclosed fence runs to the end while streaming`() {
        assertEquals(
            listOf(CodeFence(null, listOf("~~~ not a close", "still code"))),
            parseMarkdown("```\n~~~ not a close\nstill code"),
        )
    }

    @Test
    fun `backticks closed on the same line are prose not a fence`() {
        assertEquals(listOf(Paragraph("```foo``` bar")), parseMarkdown("```foo``` bar"))
    }

    @Test
    fun `a table honours alignment and pads a ragged row`() {
        val table = parseMarkdown("| Name | Qty | Note |\n|:--|:-:|--:|\n| a | 1 |\n| b | 2 | x | extra |")
        assertEquals(
            listOf(
                Table(
                    header = listOf("Name", "Qty", "Note"),
                    alignments = listOf(ColumnAlignment.START, ColumnAlignment.CENTER, ColumnAlignment.END),
                    rows = listOf(listOf("a", "1", ""), listOf("b", "2", "x")),
                ),
            ),
            table,
        )
    }

    @Test
    fun `a table delimiter is not a rule but a lone dash line is`() {
        assertEquals(
            listOf(Table(listOf("a", "b"), List(2) { ColumnAlignment.START }, emptyList())),
            parseMarkdown("a | b\n--|--"),
        )
        assertEquals(listOf(Paragraph("x"), Rule, Rule, Rule), parseMarkdown("x\n\n---\n***\n_ _ _"))
    }

    @Test
    fun `text above dashes becomes a setext heading`() {
        assertEquals(listOf(Heading(2, "Title")), parseMarkdown("Title\n---"))
    }

    @Test
    fun `quotes parse their own content`() {
        assertEquals(
            listOf(Quote(listOf(Paragraph("wise words"), ListBlock(false, listOf(ListItem("a", 0, null, null)))))),
            parseMarkdown("> wise\n> words\n>\n> - a"),
        )
    }

    @Test
    fun `bold italic strike and code carry their styles`() {
        val text = inline("a **b** *c* ~~d~~ `e`")
        assertEquals("a b c d e", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(2 until 3, bold.start until bold.end)
        assertTrue(text.spanStyles.any { it.item.fontStyle != null && it.start == 4 })
        assertTrue(text.spanStyles.any { it.item.textDecoration != null && it.start == 6 })
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && it.start == 8 })
    }

    @Test
    fun `links and bare urls open through the platform`() {
        val text = inline("see [docs](https://a.dev/x) or https://b.dev/y.")
        assertEquals("see docs or https://b.dev/y.", text.text)
        val urls = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://a.dev/x", "https://b.dev/y"), urls)
    }

    @Test
    fun `a link with an unsafe scheme keeps only its label`() {
        val text = inline("[hi](javascript:alert(1))")
        assertEquals("hi", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test
    fun `escaped and unmatched markers stay literal`() {
        assertEquals("*not* 2 * 3 **open", inline("\\*not\\* 2 * 3 **open").text)
    }

    @Test
    fun `snake case words are not italicised`() {
        val text = inline("use snake_case_word and _real_")
        assertEquals("use snake_case_word and real", text.text)
        assertEquals(1, text.spanStyles.size)
    }
}
