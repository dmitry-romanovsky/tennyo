package app.tennyo.mobile.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.layout.WorkspaceDestinationItem
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.generated.resources.Res
import app.tennyo.mobile.generated.resources.destination_chat
import app.tennyo.mobile.generated.resources.destination_git
import app.tennyo.mobile.generated.resources.panel_files
import app.tennyo.mobile.generated.resources.panel_launch
import app.tennyo.mobile.generated.resources.panel_terminal
import app.tennyo.mobile.generated.resources.panels_title
import org.jetbrains.compose.resources.stringResource

/**
 * The panels sheet (screen N3): only what this host serves. Nothing is greyed out — a panel the host cannot offer
 * is simply not listed, so the sheet never invites a tap that would do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePanelsSheet(
    panels: List<WorkspacePanel>,
    openPanel: WorkspacePanel?,
    onPanelSelected: (WorkspacePanel) -> Unit,
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
                .padding(horizontal = Spacing.large, vertical = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            androidx.compose.material3.Text(
                text = stringResource(Res.string.panels_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.small),
            )
            panels.forEach { panel ->
                TennyoListRow(
                    title = panel.label(),
                    onClick = { onPanelSelected(panel) },
                    selected = panel == openPanel,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        Icon(imageVector = panel.icon(), contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
internal fun WorkspacePanel.icon(): ImageVector = when (this) {
    WorkspacePanel.FILES -> TennyoIcons.Folder
    WorkspacePanel.TERMINAL -> TennyoIcons.Terminal
    WorkspacePanel.LAUNCH -> TennyoIcons.PlayArrow
}

@Composable
internal fun WorkspacePanel.label(): String = stringResource(
    when (this) {
        WorkspacePanel.FILES -> Res.string.panel_files
        WorkspacePanel.TERMINAL -> Res.string.panel_terminal
        WorkspacePanel.LAUNCH -> Res.string.panel_launch
    },
)

/** The strip's view of a destination, with the count it wants noticed. */
@Composable
internal fun WorkspaceDestination.toItem(state: WorkspaceState): WorkspaceDestinationItem =
    WorkspaceDestinationItem(
        key = name,
        label = stringResource(
            when (this) {
                WorkspaceDestination.CHAT -> Res.string.destination_chat
                WorkspaceDestination.GIT -> Res.string.destination_git
            },
        ),
        icon = when (this) {
            WorkspaceDestination.CHAT -> TennyoIcons.Forum
            WorkspaceDestination.GIT -> TennyoIcons.AccountTree
        },
        badge = when (this) {
            WorkspaceDestination.CHAT -> null
            WorkspaceDestination.GIT -> state.changedFileCount?.takeIf { it > 0 }
        },
    )
