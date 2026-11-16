package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_selection_none
import app.tennyo.mobile.feature.git.generated.resources.git_selection_summary
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GitListLabel(label: String, modifier: Modifier = Modifier, count: Int? = null) {
    Text(
        text = if (count == null) label.uppercase() else "${label.uppercase()} · $count",
        modifier = modifier.padding(top = Spacing.medium),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun GitEmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.large),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Preview(showBackground = true, widthDp = 280, heightDp = 40)
@Composable
private fun GitListLabelPreview() {
    TennyoTheme {
        GitListLabel(label = "Changes", count = 5)
    }
}

@Preview(showBackground = true, widthDp = 280, heightDp = 40)
@Composable
private fun GitListLabelNoCountPreview() {
    TennyoTheme {
        GitListLabel(label = "History")
    }
}

@Preview(showBackground = true, widthDp = 300, heightDp = 120)
@Composable
private fun GitEmptyStatePreview() {
    TennyoTheme {
        GitEmptyState(message = "No changes to commit")
    }
}

/**
 * "3 of 4 selected · +73 −64" — what the commit button is about to act on, stated before it is pressed, with the
 * line counts of the selection rather than of everything changed.
 */
@Composable
internal fun GitSelectionSummary(
    changes: List<GitChange>,
    selectedChangeIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val selected = changes.filter { it.id in selectedChangeIds }
    val text = if (selected.isEmpty()) {
        stringResource(Res.string.git_selection_none, changes.size)
    } else {
        stringResource(
            Res.string.git_selection_summary,
            selected.size,
            changes.size,
            selected.sumOf { it.addedLines ?: 0 },
            selected.sumOf { it.deletedLines ?: 0 },
        )
    }
    Text(
        text = text,
        modifier = modifier.padding(top = Spacing.medium),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}
