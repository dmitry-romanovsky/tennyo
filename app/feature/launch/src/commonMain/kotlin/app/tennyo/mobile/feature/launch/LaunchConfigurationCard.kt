package app.tennyo.mobile.feature.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.contract.LaunchConfiguration
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProjectAvatar
import app.tennyo.mobile.design.TennyoStatusChip
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.launch.generated.resources.Res
import app.tennyo.mobile.feature.launch.generated.resources.launch_action
import app.tennyo.mobile.feature.launch.generated.resources.launch_action_details
import app.tennyo.mobile.feature.launch.generated.resources.launch_status_running
import org.jetbrains.compose.resources.stringResource

/** One launch configuration as a card: identity and status on the left, its single action on the right. */
@Composable
internal fun LaunchConfigurationCard(
    configuration: LaunchConfiguration,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val running = configuration.isRunning
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.card,
        color = if (running) {
            colors.tertiaryContainer.copy(alpha = ComponentAlphas.statusContainer)
        } else {
            colors.surfaceContainerLow
        },
        border = BorderStroke(
            Borders.thin,
            if (running) colors.tertiary.copy(alpha = ComponentAlphas.faintLine) else colors.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TennyoProjectAvatar(text = configuration.name, selected = running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Text(
                    text = configuration.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(configuration.type, configuration.folder).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (running) {
                    TennyoStatusChip(
                        label = stringResource(Res.string.launch_status_running),
                        tone = TennyoStatusTone.Connected,
                    )
                }
            }
            TennyoButton(
                label = stringResource(if (running) Res.string.launch_action_details else Res.string.launch_action),
                onClick = onAction,
                icon = if (running) TennyoIcons.Info else TennyoIcons.PlayArrow,
                style = if (running) TennyoButtonStyle.Secondary else TennyoButtonStyle.Success,
            )
        }
    }
}
