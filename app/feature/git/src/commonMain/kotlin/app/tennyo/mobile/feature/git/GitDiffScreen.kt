package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.TennyoTag
import app.tennyo.mobile.design.TennyoUnifiedDiffRow
import app.tennyo.mobile.design.TennyoUnifiedDiffSurface
import app.tennyo.mobile.design.TennyoWorkingIndicator
import app.tennyo.mobile.design.editor.EditorHighlightPalette
import app.tennyo.mobile.design.editor.EditorLanguage
import app.tennyo.mobile.design.editor.TextMateLiteGrammarRegistry
import app.tennyo.mobile.design.editor.detectEditorLanguage
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_binary_message
import app.tennyo.mobile.feature.git.generated.resources.git_binary_title
import app.tennyo.mobile.feature.git.generated.resources.git_change_position
import app.tennyo.mobile.feature.git.generated.resources.git_diff
import app.tennyo.mobile.feature.git.generated.resources.git_diff_folded_lines
import app.tennyo.mobile.feature.git.generated.resources.git_diff_show_unchanged
import app.tennyo.mobile.feature.git.generated.resources.git_diff_too_complex
import app.tennyo.mobile.feature.git.generated.resources.git_diff_too_large
import app.tennyo.mobile.feature.git.generated.resources.git_diff_unavailable
import app.tennyo.mobile.feature.git.generated.resources.git_loading
import app.tennyo.mobile.feature.git.generated.resources.git_next_change
import app.tennyo.mobile.feature.git.generated.resources.git_open_in_editor
import app.tennyo.mobile.feature.git.generated.resources.git_open_in_ide
import app.tennyo.mobile.feature.git.generated.resources.git_previous_change
import app.tennyo.mobile.feature.git.generated.resources.git_rollback
import app.tennyo.mobile.feature.git.generated.resources.git_working_tree_to_head
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GitDiffScreen(
    response: GitDiff?,
    presentation: GitDiffPresentation?,
    failure: GitFault?,
    loading: Boolean,
    /** Back to the commit this file belongs to; `null` when the diff was opened on its own and only closes. */
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    changeIndex: Int = 0,
    changeCount: Int = 1,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    /** What the two sides are: the working tree against `HEAD`, or one commit against its parent. */
    comparison: String = stringResource(Res.string.git_working_tree_to_head),
    /** Discards this working-tree change after asking; `null` where there is nothing to roll back. */
    onRollback: (() -> Unit)? = null,
    /** Opens this file in the computer's editor; `null` where the host cannot. */
    onOpenInEditor: (() -> Unit)? = null,
    bottomInset: Dp = Spacing.none,
) {
    val path = response?.afterPath ?: response?.beforePath
    Column(modifier = modifier) {
        GitSheetHeader(
            title = path?.substringAfterLast('/') ?: stringResource(Res.string.git_diff),
            subtitle = listOfNotNull(response?.beforePath, response?.afterPath)
                .map { it.substringBeforeLast('/', "").ifEmpty { "/" } }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" → "),
            onBack = onBack,
            onClose = onClose,
            actions = {
                onOpenInEditor?.let {
                    TennyoIconButton(
                        icon = TennyoIcons.Edit,
                        contentDescription = stringResource(Res.string.git_open_in_editor),
                        onClick = it,
                        iconSize = ComponentSizes.icon,
                    )
                }
                onRollback?.let {
                    TennyoIconButton(
                        icon = TennyoIcons.Undo,
                        contentDescription = stringResource(Res.string.git_rollback),
                        onClick = it,
                        iconSize = ComponentSizes.icon,
                    )
                }
            },
        )
        GitDiffToolbar(
            comparison = comparison,
            presentation = presentation as? GitDiffPresentation.Content,
            changeIndex = changeIndex,
            changeCount = changeCount,
            onPrevious = onPrevious,
            onNext = onNext,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> TennyoWorkingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    label = stringResource(Res.string.git_loading)
                )

                failure != null -> TennyoStatusBanner(
                    title = stringResource(Res.string.git_diff_unavailable),
                    message = failure.displayMessage(),
                    tone = TennyoStatusTone.Failed,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                )

                presentation != null -> GitDiffPresentation(
                    presentation = presentation,
                    scrollIdentity = response?.let { "${it.repositoryId}:${it.changeId}" }
                        ?: "pending",
                    language = detectEditorLanguage(path.orEmpty()),
                    modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                )
            }
        }
    }
}

/** Under the file name: the compared sides, the line counts, and the stepper through the list it came from. */
@Composable
private fun GitDiffToolbar(
    comparison: String,
    presentation: GitDiffPresentation.Content?,
    changeIndex: Int,
    changeCount: Int,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = Spacing.large, end = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        TennyoTag(comparison)
        presentation?.let {
            Text(
                text = "+${it.addedLines}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "−${it.deletedLines}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.weight(1f))
        if (changeCount > 1) {
            TennyoIconButton(
                icon = TennyoIcons.NavigateBefore,
                contentDescription = stringResource(Res.string.git_previous_change),
                onClick = { onPrevious?.invoke() },
                enabled = onPrevious != null,
                iconSize = ComponentSizes.icon,
            )
            Text(
                text = stringResource(Res.string.git_change_position, changeIndex + 1, changeCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TennyoIconButton(
                icon = TennyoIcons.NavigateNext,
                contentDescription = stringResource(Res.string.git_next_change),
                onClick = { onNext?.invoke() },
                enabled = onNext != null,
                iconSize = ComponentSizes.icon,
            )
        } else {
            // Keeps the strip one button tall when there is nothing to step through.
            Spacer(Modifier.height(ComponentSizes.iconButton))
        }
    }
}

@Composable
private fun GitDiffPresentation(
    presentation: GitDiffPresentation,
    scrollIdentity: String,
    language: EditorLanguage,
    modifier: Modifier = Modifier,
) {
    when (presentation) {
        GitDiffPresentation.Binary -> TennyoStatusBanner(
            title = stringResource(Res.string.git_binary_title),
            message = stringResource(Res.string.git_binary_message),
            modifier = modifier,
            tone = TennyoStatusTone.Warning,
        )

        is GitDiffPresentation.Unavailable -> TennyoStatusBanner(
            title = stringResource(Res.string.git_diff_unavailable),
            message = presentation.reason,
            modifier = modifier,
            tone = TennyoStatusTone.Warning,
        )

        GitDiffPresentation.TooLarge -> TennyoStatusBanner(
            title = stringResource(Res.string.git_diff_too_large),
            message = stringResource(Res.string.git_open_in_ide),
            modifier = modifier,
            tone = TennyoStatusTone.Warning,
        )

        GitDiffPresentation.TooComplex -> TennyoStatusBanner(
            title = stringResource(Res.string.git_diff_too_complex),
            message = stringResource(Res.string.git_open_in_ide),
            modifier = modifier,
            tone = TennyoStatusTone.Warning,
        )

        is GitDiffPresentation.Content -> {
            val palette = EditorHighlightPalette.current()
            var expandedFolds by remember(scrollIdentity) { mutableStateOf(setOf<Int>()) }
            val sourceRows = remember(presentation, expandedFolds) { presentation.unifiedRows(expandedFolds) }
            val foldLabels = sourceRows.filter { it.foldKey != null }.associate { row ->
                row.foldKey to pluralStringResource(Res.plurals.git_diff_folded_lines, row.foldedLines, row.foldedLines)
            }
            val expandLabel = stringResource(Res.string.git_diff_show_unchanged)
            val onExpand = { key: Int -> expandedFolds += key }
            // Plain rows show at once; their highlighted copy replaces them when the background pass is done.
            val plainRows = remember(sourceRows, foldLabels) {
                sourceRows.map { it.toDiffRow(foldLabels, expandLabel, onExpand) }
            }
            var rows by remember(plainRows) { mutableStateOf(plainRows) }
            LaunchedEffect(plainRows, language, palette) {
                rows = withContext(Dispatchers.Default) {
                    sourceRows.map { row ->
                        row.toDiffRow(foldLabels, expandLabel, onExpand) { text ->
                            text.highlightCode(language, palette)
                        }
                    }
                }
            }
            TennyoUnifiedDiffSurface(
                rows = rows,
                scrollIdentity = scrollIdentity,
                modifier = modifier.heightIn(max = ComponentSizes.outputMaxHeight),
            )
        }
    }
}

private fun GitUnifiedDiffRow.toDiffRow(
    foldLabels: Map<Int?, String>,
    expandLabel: String,
    onExpand: (Int) -> Unit,
    highlight: ((String) -> AnnotatedString)? = null,
) = TennyoUnifiedDiffRow(
    oldLineNumber = oldLineNumber,
    newLineNumber = newLineNumber,
    marker = marker,
    text = foldKey?.let { foldLabels[it] } ?: text,
    added = added,
    removed = removed,
    annotatedText = if (foldKey == null) highlight?.invoke(text) else null,
    onClick = foldKey?.let { key -> { onExpand(key) } },
    onClickLabel = foldKey?.let { expandLabel },
)

private fun String.highlightCode(language: EditorLanguage, palette: EditorHighlightPalette) =
    buildAnnotatedString {
        append(this@highlightCode)
        TextMateLiteGrammarRegistry.grammarFor(language)
            ?.highlight(this@highlightCode)
            ?.forEach { span ->
                addStyle(palette.styleFor(span.style), span.start, span.end)
            }
    }

@Preview(name = "Diff - unified long lines", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitLongDiffPreview() {
    GitDiffPreviewSurface { state ->
        val key = GitDiffKey("repo", "long")
        val response = GitDiff(
            repositoryId = "repo",
            changeId = "long",
            beforePath = "src/UserService.kt",
            afterPath = "src/UserService.kt",
            beforeContent = "unchanged\nval oldValue = ${"x".repeat(180)}",
            afterContent = "unchanged\nval newValue = ${"y".repeat(180)}\nadded",
        )
        state.copy(
            selectedDiff = key,
            diffs = state.diffs + (key to response),
            diffPresentations = state.diffPresentations + (key to response.toPresentation()),
        )
    }
}

@Preview(name = "Diff - folded long file", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitFoldedDiffPreview() {
    GitDiffPreviewSurface { state ->
        val key = GitDiffKey("repo", "folded")
        val lines = List(60) { "val line$it = $it" }
        val response = GitDiff(
            repositoryId = "repo",
            changeId = "folded",
            beforePath = "src/Constants.kt",
            afterPath = "src/Constants.kt",
            beforeContent = lines.joinToString("\n"),
            afterContent = lines.toMutableList().apply { this[30] = "val line30 = 3000" }.joinToString("\n"),
        )
        state.copy(
            selectedDiff = key,
            diffs = state.diffs + (key to response),
            diffPresentations = state.diffPresentations + (key to response.toPresentation()),
        )
    }
}

@Preview(name = "Diff - binary", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitBinaryDiffPreview() {
    GitDiffPreviewSurface { state ->
        val key = GitDiffKey("repo", "binary")
        val response = GitDiff("repo", "binary", afterPath = "assets/logo.png", isBinary = true)
        state.copy(
            selectedDiff = key,
            diffs = state.diffs + (key to response),
            diffPresentations = state.diffPresentations + (key to response.toPresentation()),
        )
    }
}

@Preview(name = "Diff - unavailable", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun GitUnavailableDiffPreview() {
    GitDiffPreviewSurface { state ->
        val key = GitDiffKey("repo", "unavailable")
        val response = GitDiff(
            "repo",
            "unavailable",
            afterPath = "generated.txt",
            unavailableReason = "The computer did not provide this diff.",
        )
        state.copy(
            selectedDiff = key,
            diffs = state.diffs + (key to response),
            diffPresentations = state.diffPresentations + (key to response.toPresentation()),
        )
    }
}

@Composable
private fun GitDiffPreviewSurface(transform: (GitState) -> GitState) {
    GitScreenPreview(transform) { git, _, _, modifier ->
        val key = requireNotNull(git.selectedDiff)
        GitDiffScreen(
            response = git.diffs[key],
            presentation = git.diffPresentations[key],
            failure = git.requestFailures[GitRequestKey.Diff(key)],
            loading = GitRequestKey.Diff(key) in git.loadingKeys,
            onBack = null,
            onClose = {},
            changeIndex = 0,
            changeCount = 2,
            modifier = modifier.fillMaxSize(),
        )
    }
}
