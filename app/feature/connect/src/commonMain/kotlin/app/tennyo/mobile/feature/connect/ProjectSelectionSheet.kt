package app.tennyo.mobile.feature.connect

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.TennyoProjectRow
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.connect_project_selection_cancel
import app.tennyo.mobile.feature.connect.generated.resources.connect_project_selection_description
import app.tennyo.mobile.feature.connect.generated.resources.connect_project_selection_this_host
import app.tennyo.mobile.feature.connect.generated.resources.connect_project_selection_title
import org.jetbrains.compose.resources.stringResource


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectSelectionSheet(
    projectSelection: ProjectSelectionUiState,
    isStartingSession: Boolean,
    onDismiss: () -> Unit,
    onProjectSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { nextValue -> nextValue != SheetValue.Hidden },
        ),
        dragHandle = null,
        contentWindowInsets = { WindowInsets() },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TennyoBottomSheetHeader(
                title = stringResource(
                    Res.string.connect_project_selection_title,
                    projectSelection.companionLabel.ifBlank {
                        stringResource(Res.string.connect_project_selection_this_host)
                    },
                ),
                actionLabel = stringResource(Res.string.connect_project_selection_cancel),
                onAction = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(horizontal = Spacing.large),
                    contentPadding = PaddingValues(
                        top = Spacing.large,
                        bottom = safeDrawingBottomInset(Spacing.large)
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    item {
                        Text(
                            text = stringResource(Res.string.connect_project_selection_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(
                        items = projectSelection.projects,
                        key = { project -> project.projectId },
                    ) { project ->
                        TennyoProjectRow(
                            title = project.projectName,
                            subtitle = project.projectPath?.takeIf { it.isNotBlank() }.orEmpty(),
                            onClick = { onProjectSelected(project.projectId) },
                            enabled = !isStartingSession,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                @Suppress("RemoveRedundantQualifierName")
                androidx.compose.animation.AnimatedVisibility(
                    visible = isStartingSession,
                    modifier = Modifier.matchParentSize(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(ComponentAlphas.disabledContent)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TennyoProgressIndicator()
                    }
                }
            }
        }
    }
}
