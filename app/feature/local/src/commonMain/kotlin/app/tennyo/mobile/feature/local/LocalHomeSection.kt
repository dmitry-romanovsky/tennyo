package app.tennyo.mobile.feature.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_cancel
import app.tennyo.mobile.feature.local.generated.resources.local_home_clone
import app.tennyo.mobile.feature.local.generated.resources.local_home_delete
import app.tennyo.mobile.feature.local.generated.resources.local_home_delete_confirm
import app.tennyo.mobile.feature.local.generated.resources.local_home_delete_explanation
import app.tennyo.mobile.feature.local.generated.resources.local_home_delete_title
import app.tennyo.mobile.feature.local.generated.resources.local_home_new
import app.tennyo.mobile.feature.local.generated.resources.local_home_recent
import app.tennyo.mobile.feature.local.generated.resources.local_workspace_storage_note
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Home's actions for on-device workspaces: create one, or clone one from Git. */
@Composable
fun LocalWorkspaceActions(onIntent: (LocalHomeIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            ActionTile(
                label = stringResource(Res.string.local_home_new),
                icon = TennyoIcons.Add,
                onClick = { onIntent(LocalHomeIntent.OpenCreate) },
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                label = stringResource(Res.string.local_home_clone),
                icon = TennyoIcons.Download,
                onClick = { onIntent(LocalHomeIntent.OpenClone) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(Res.string.local_workspace_storage_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Home's list of on-device workspaces, newest first; deleting one asks first in a sheet. */
@Composable
fun LocalWorkspaceList(
    workspaces: List<LocalWorkspace>,
    onIntent: (LocalHomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deletingSlug by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.local_home_recent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = Spacing.extraSmall),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = safeDrawingBottomInset(Spacing.extraLarge)),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            items(workspaces, key = LocalWorkspace::slug) { workspace ->
                WorkspaceRow(
                    workspace = workspace,
                    onClick = { onIntent(LocalHomeIntent.OpenWorkspace(workspace.slug)) },
                    onDelete = { deletingSlug = workspace.slug },
                )
            }
        }
    }
    workspaces.firstOrNull { it.slug == deletingSlug }?.let { workspace ->
        DeleteWorkspaceSheet(
            workspace = workspace,
            onDelete = {
                deletingSlug = null
                onIntent(LocalHomeIntent.DeleteWorkspace(workspace.slug))
            },
            onDismiss = { deletingSlug = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteWorkspaceSheet(
    workspace: LocalWorkspace,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(Res.string.local_home_delete_title, workspace.name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.local_home_delete_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
            ) {
                TennyoButton(
                    label = stringResource(Res.string.local_cancel),
                    onClick = onDismiss,
                    style = TennyoButtonStyle.Text,
                )
                TennyoButton(
                    label = stringResource(Res.string.local_home_delete_confirm),
                    icon = TennyoIcons.Delete,
                    onClick = onDelete,
                    style = TennyoButtonStyle.Danger,
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(ComponentSizes.actionTileHeight).clickable(onClick = onClick),
        shape = Corners.sm,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Borders.regular, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ComponentSizes.icon),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WorkspaceRow(
    workspace: LocalWorkspace,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val created = Instant.fromEpochMilliseconds(workspace.createdAtEpochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = created.month.name.take(MONTH_ABBREVIATION).lowercase().replaceFirstChar(Char::uppercase)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.extraSmall, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Box(
            modifier = Modifier
                .size(ComponentSizes.projectAvatar)
                .clip(Corners.sm)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = workspace.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${created.day} $month ${created.year}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TennyoIconButton(
            icon = TennyoIcons.Delete,
            contentDescription = stringResource(Res.string.local_home_delete, workspace.name),
            onClick = onDelete,
        )
    }
}

private const val MONTH_ABBREVIATION = 3

@Preview(showBackground = true, widthDp = 380, heightDp = 360)
@Composable
private fun LocalHomeSectionPreview() {
    TennyoTheme {
        Column {
            LocalWorkspaceActions(onIntent = {})
            LocalWorkspaceList(
                workspaces = listOf(LocalWorkspace("Notes", "notes", createdAtEpochMillis = 0)),
                onIntent = {},
                modifier = Modifier.height(ComponentSizes.actionTileHeight * 2),
            )
        }
    }
}
