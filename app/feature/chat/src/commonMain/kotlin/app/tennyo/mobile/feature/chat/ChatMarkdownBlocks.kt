package app.tennyo.mobile.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import app.tennyo.mobile.design.TennyoCodeSurface
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.editorTextStyle
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_markdown_show_less
import app.tennyo.mobile.feature.chat.generated.resources.chat_markdown_show_lines
import app.tennyo.mobile.feature.chat.generated.resources.chat_markdown_show_rows
import org.jetbrains.compose.resources.stringResource

/** Longer code folds to this many lines, and a longer table to this many rows, until the reader opens it. */
private const val COLLAPSED_CODE_LINES = 14
private const val COLLAPSED_TABLE_ROWS = 8

private const val HEADING_LARGE = 1
private const val HEADING_MEDIUM = 2

/** The parsed blocks of one agent message, stacked with the gap between paragraphs. */
@Composable
internal fun MarkdownContent(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        blocks.forEachIndexed { index, block ->
            key(index) { MarkdownBlockView(block) }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, modifier: Modifier = Modifier) {
    when (block) {
        is MarkdownBlock.Heading -> InlineText(block.text, headingStyle(block.level), modifier)
        is MarkdownBlock.Paragraph -> InlineText(block.text, MaterialTheme.typography.bodyMedium, modifier)
        is MarkdownBlock.ListBlock -> MarkdownList(block, modifier)
        is MarkdownBlock.Quote -> MarkdownQuote(block, modifier)
        is MarkdownBlock.CodeFence -> MarkdownCode(block, modifier)
        is MarkdownBlock.Table -> MarkdownTable(block, modifier)
        MarkdownBlock.Rule -> HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    HEADING_LARGE -> MaterialTheme.typography.titleLarge
    HEADING_MEDIUM -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
    softWrap: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val codeFont = editorTextStyle().fontFamily
    val styles = remember(colors, codeFont) {
        InlineStyles(
            link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
            code = SpanStyle(fontFamily = codeFont, background = colors.surfaceVariant),
        )
    }
    Text(
        text = remember(text, styles) { inlineMarkdown(text, styles) },
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        softWrap = softWrap,
    )
}

@Composable
private fun MarkdownList(block: MarkdownBlock.ListBlock, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        block.items.forEachIndexed { index, item ->
            key(index) {
                Row(
                    modifier = Modifier.padding(start = Spacing.large * item.depth),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    ListMarker(item)
                    InlineText(item.text, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ListMarker(item: ListItem, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val box = modifier.size(ComponentSizes.compactIcon)
    when {
        item.checked == true -> Icon(TennyoIcons.Check, null, box, MaterialTheme.colorScheme.tertiary)
        item.checked == false -> Box(
            box.border(Borders.selectionControl, MaterialTheme.colorScheme.outline, Corners.xs),
        )
        else -> Text(
            text = item.number?.let { "$it." } ?: "•",
            modifier = modifier.widthIn(min = ComponentSizes.compactIcon),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarkdownQuote(block: MarkdownBlock.Quote, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(Borders.focus)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        MarkdownContent(block.blocks, Modifier.padding(start = Spacing.medium))
    }
}

@Composable
private fun MarkdownCode(block: MarkdownBlock.CodeFence, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val folded = block.lines.size > COLLAPSED_CODE_LINES && !expanded
    TennyoCodeSurface(modifier = modifier.fillMaxWidth().animateContentSize()) {
        block.language?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = block.lines.let { if (folded) it.take(COLLAPSED_CODE_LINES) else it }
                .joinToString("\n").ifEmpty { " " },
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            style = editorTextStyle(),
            softWrap = false,
        )
        if (block.lines.size > COLLAPSED_CODE_LINES) {
            ExpandToggle(
                expanded = expanded,
                showAll = stringResource(Res.string.chat_markdown_show_lines, block.lines.size),
                onToggle = { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val folded = block.rows.size > COLLAPSED_TABLE_ROWS && !expanded
    val rows = if (folded) block.rows.take(COLLAPSED_TABLE_ROWS) else block.rows
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        // Cells stay on one line so every row has one height and the columns line up; a wide table scrolls.
        Row(
            Modifier
                .clip(Corners.xs)
                .border(Borders.regular, outline, Corners.xs)
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Min),
        ) {
            block.header.forEachIndexed { column, title ->
                if (column > 0) VerticalDivider(color = outline)
                val textAlign = block.alignments[column].textAlign
                Column(Modifier.width(IntrinsicSize.Max)) {
                    TableCell(
                        text = title,
                        textAlign = textAlign,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        weight = FontWeight.SemiBold,
                    )
                    rows.forEach { row ->
                        HorizontalDivider(color = outline)
                        TableCell(row[column], textAlign)
                    }
                }
            }
        }
        if (block.rows.size > COLLAPSED_TABLE_ROWS) {
            ExpandToggle(
                expanded = expanded,
                showAll = stringResource(Res.string.chat_markdown_show_rows, block.rows.size),
                onToggle = { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
) {
    InlineText(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = weight),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        textAlign = textAlign,
        softWrap = false,
    )
}

@Composable
private fun ExpandToggle(expanded: Boolean, showAll: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = if (expanded) stringResource(Res.string.chat_markdown_show_less) else showAll,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = Spacing.extraSmall),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

private val ColumnAlignment.textAlign: TextAlign
    get() = when (this) {
        ColumnAlignment.START -> TextAlign.Start
        ColumnAlignment.CENTER -> TextAlign.Center
        ColumnAlignment.END -> TextAlign.End
    }
