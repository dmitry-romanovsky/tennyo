package app.tennyo.mobile.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.mobile.design.editor.LocalEditorSoftWrap
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.editorTextStyle

enum class TennyoCodeTone {
    Default,
    Muted,
    Keyword,
    String,
    Number,
    Annotation,
    Function,
    Added,
    Removed,
}

@Composable
fun TennyoCodeSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(Corners.xs)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Borders.regular, MaterialTheme.colorScheme.outlineVariant, Corners.xs)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        content = content,
    )
}

@Composable
fun TennyoCodeText(
    text: String,
    modifier: Modifier = Modifier,
    tone: TennyoCodeTone = TennyoCodeTone.Default,
    softWrap: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = tennyoCodeColor(tone),
        maxLines = if (softWrap) Int.MAX_VALUE else 1,
        overflow = if (softWrap) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

data class TennyoUnifiedDiffRow(
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val marker: String,
    val text: String,
    val added: Boolean = false,
    val removed: Boolean = false,
    val annotatedText: androidx.compose.ui.text.AnnotatedString? = null,
    /** Makes the whole row a button, in its gutter and its code alike; it is styled like a hunk header. */
    val onClick: (() -> Unit)? = null,
    val onClickLabel: String? = null,
) {
    internal val emphasised: Boolean get() = onClick != null
}

private fun Modifier.diffRowClickable(row: TennyoUnifiedDiffRow): Modifier =
    row.onClick?.let { clickable(onClickLabel = row.onClickLabel, role = Role.Button, onClick = it) } ?: this

/**
 * A unified diff: marker and old/new line-number gutters beside the code. Long lines scroll sideways, or wrap when
 * [softWrap] is on — the editor's own setting, so a file reads the same in the editor and in its diff.
 */
@Composable
fun TennyoUnifiedDiffSurface(
    rows: List<TennyoUnifiedDiffRow>,
    modifier: Modifier = Modifier,
    scrollIdentity: String = "default",
    softWrap: Boolean = LocalEditorSoftWrap.current,
) {
    val verticalScrollState = remember(scrollIdentity) { ScrollState(0) }
    val horizontalScrollState = remember(scrollIdentity) { ScrollState(0) }
    val textStyle = editorTextStyle()
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (softWrap) {
        WrappedUnifiedDiff(rows, verticalScrollState, modifier)
        return
    }

    BoxWithConstraints(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        val codeViewportWidth = maxWidth
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState),
            ) {
                Row {
                    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                        rows.forEach { row ->
                            Text(
                                text = row.marker.ifEmpty { " " },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .diffRowClickable(row)
                                    .background(unifiedDiffGutterBackground(row))
                                    .padding(horizontal = Spacing.extraSmall),
                                style = textStyle,
                                color = unifiedDiffRowContent(row.added, row.removed),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                        rows.forEach { row ->
                            Text(
                                text = row.oldLineNumber?.toString() ?: " ",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .diffRowClickable(row)
                                    .background(unifiedDiffGutterBackground(row)),
                                style = textStyle,
                                color = gutterColor,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                        rows.forEach { row ->
                            Text(
                                text = row.newLineNumber?.toString() ?: " ",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .diffRowClickable(row)
                                    .background(unifiedDiffGutterBackground(row))
                                    .padding(horizontal = Spacing.extraSmall),
                                style = textStyle,
                                color = gutterColor,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = Borders.thin,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .panScroll(verticalScrollState, horizontalScrollState),
            ) {
                rows.forEach { row ->
                    val rowBackground = if (row.emphasised) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        unifiedDiffRowBackground(row.added, row.removed)
                    }
                    Text(
                        text = row.annotatedText
                            ?: androidx.compose.ui.text.AnnotatedString(row.text.ifEmpty { " " }),
                        modifier = Modifier
                            .widthIn(min = codeViewportWidth)
                            .diffRowClickable(row)
                            .background(rowBackground)
                            .padding(horizontal = Spacing.small),
                        style = textStyle,
                        color = if (row.emphasised) MaterialTheme.colorScheme.primary else unifiedDiffRowContent(
                            row.added,
                            row.removed
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

/**
 * The wrapped diff lays out row by row, each with its own gutter, so a line that wraps keeps its marker and numbers
 * beside its first visual line. The number columns are as wide as the widest number, measured once.
 */
@Composable
private fun WrappedUnifiedDiff(
    rows: List<TennyoUnifiedDiffRow>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val textStyle = editorTextStyle()
    val measurer = rememberTextMeasurer()
    val widestNumber = rows.maxOfOrNull { maxOf(it.oldLineNumber ?: 0, it.newLineNumber ?: 0) }?.toString() ?: "0"
    val numberWidth = with(LocalDensity.current) {
        measurer.measure(widestNumber, textStyle).size.width.toDp()
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scrollState),
    ) {
        rows.forEach { row ->
            val content = if (row.emphasised) {
                MaterialTheme.colorScheme.primary
            } else {
                unifiedDiffRowContent(row.added, row.removed)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .diffRowClickable(row)
                    .background(unifiedDiffGutterBackground(row)),
            ) {
                Text(
                    text = row.marker.ifEmpty { " " },
                    modifier = Modifier.padding(horizontal = Spacing.extraSmall),
                    style = textStyle,
                    color = unifiedDiffRowContent(row.added, row.removed),
                )
                listOf(row.oldLineNumber, row.newLineNumber).forEach { number ->
                    Text(
                        text = number?.toString() ?: " ",
                        modifier = Modifier.width(numberWidth + Spacing.extraSmall).padding(end = Spacing.extraSmall),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = Borders.thin,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = row.annotatedText ?: AnnotatedString(row.text.ifEmpty { " " }),
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.small),
                    style = textStyle,
                    color = content,
                )
            }
        }
    }
}

@Composable
private fun unifiedDiffGutterBackground(row: TennyoUnifiedDiffRow) =
    if (row.emphasised) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        unifiedDiffRowBackground(row.added, row.removed)
    }

@Composable
private fun unifiedDiffRowBackground(added: Boolean, removed: Boolean): Color = when {
    added -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = ComponentAlphas.selectedContainer)
    removed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = ComponentAlphas.selectedContainer)
    else -> Color.Transparent
}

@Composable
private fun unifiedDiffRowContent(added: Boolean, removed: Boolean): Color = when {
    added -> MaterialTheme.colorScheme.tertiary
    removed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun tennyoCodeColor(tone: TennyoCodeTone): Color =
    when (tone) {
        TennyoCodeTone.Default -> MaterialTheme.colorScheme.onSurface
        TennyoCodeTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        TennyoCodeTone.Keyword -> MaterialTheme.colorScheme.primary
        TennyoCodeTone.String -> MaterialTheme.colorScheme.tertiary
        TennyoCodeTone.Number -> MaterialTheme.colorScheme.primary
        TennyoCodeTone.Annotation -> MaterialTheme.colorScheme.tertiary
        TennyoCodeTone.Function -> MaterialTheme.colorScheme.secondary
        TennyoCodeTone.Added -> MaterialTheme.colorScheme.tertiary
        TennyoCodeTone.Removed -> MaterialTheme.colorScheme.error
    }


