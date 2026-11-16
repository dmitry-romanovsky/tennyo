package app.tennyo.mobile.feature.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.paneInset
import app.tennyo.mobile.design.layout.panePadding
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.launch.generated.resources.Res
import app.tennyo.mobile.feature.launch.generated.resources.launch_action_stop
import app.tennyo.mobile.feature.launch.generated.resources.launch_close
import app.tennyo.mobile.feature.launch.generated.resources.launch_configurations_empty
import app.tennyo.mobile.feature.launch.generated.resources.launch_configurations_title
import app.tennyo.mobile.feature.launch.generated.resources.launch_finished
import app.tennyo.mobile.feature.launch.generated.resources.launch_running
import app.tennyo.mobile.feature.launch.generated.resources.launch_title
import org.jetbrains.compose.resources.stringResource


/** Launch configurations with controls beside each entry and live launch details in a sheet. */
@Composable
fun LaunchPane(
    component: LaunchPaneComponent,
    panePadding: PanePadding,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val output by component.output.collectAsState()
    LaunchPane(
        state = state,
        output = output,
        onIntent = component::onIntent,
        panePadding = panePadding,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchPane(
    state: LaunchState,
    output: LaunchOutputState = LaunchOutputState(),
    onIntent: (LaunchIntent) -> Unit,
    panePadding: PanePadding,
    modifier: Modifier = Modifier,
) {
    var launchInfoId by remember { mutableStateOf<String?>(null) }
    val launchInfo = state.configurations.firstOrNull { it.id == launchInfoId }
    if (launchInfo != null) {
        val isLaunching = launchInfo.isRunning || state.startingConfigurationId == launchInfo.id
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                TennyoBottomSheetHeader(
                    title = stringResource(Res.string.launch_title),
                    actionLabel = stringResource(Res.string.launch_close),
                    onAction = { launchInfoId = null },
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(launchInfo.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = launchInfo.type,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    launchInfo.folder?.let { folder ->
                        Text(
                            text = folder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(if (isLaunching) Res.string.launch_running else Res.string.launch_finished),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LaunchOutputPanel(
                    output = output.text,
                    modifier = Modifier.padding(horizontal = Spacing.large),
                )
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.large),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    if (isLaunching) TennyoButton(
                        label = stringResource(Res.string.launch_action_stop),
                        onClick = {
                            onIntent(LaunchIntent.Stop(launchInfo.id))
                            launchInfoId = null
                        },
                        icon = TennyoIcons.Stop,
                        style = TennyoButtonStyle.Danger,
                    )
                }
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(panePadding.contentPadding),
    ) {
        Text(
            stringResource(Res.string.launch_configurations_title),
            modifier = Modifier.paneInset().padding(vertical = Spacing.small),
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.configurations.isEmpty()) {
            Text(
                text = stringResource(Res.string.launch_configurations_empty),
                modifier = Modifier.paneInset(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.configurations, key = { it.id }) { configuration ->
                    LaunchConfigurationCard(
                        configuration = configuration,
                        onAction = {
                            launchInfoId = configuration.id
                            if (!configuration.isRunning) {
                                onIntent(LaunchIntent.Start(configuration.id))
                            } else {
                                onIntent(LaunchIntent.SelectConfiguration(configuration.id))
                            }
                        },
                        modifier = Modifier.paneInset(),
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 420, heightDp = 900, showBackground = true)
@Preview(name = "Launch pane dark", widthDp = 420, heightDp = 900, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LaunchPanePreview() {
    TennyoTheme {
        LaunchPane(
            state = previewLaunchState(),
            onIntent = {},
            panePadding = panePadding(
                innerPadding = PaddingValues(),
                layoutDirection = LayoutDirection.Ltr,
                bottomSystemBarInset = 0.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
