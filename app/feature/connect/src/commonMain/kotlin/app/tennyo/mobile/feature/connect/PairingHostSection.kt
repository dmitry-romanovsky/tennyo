package app.tennyo.mobile.feature.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.pairing_help_body
import app.tennyo.mobile.feature.connect.generated.resources.pairing_help_title
import app.tennyo.mobile.feature.connect.generated.resources.pairing_host_blocked
import app.tennyo.mobile.feature.connect.generated.resources.pairing_nearby_empty
import app.tennyo.mobile.feature.connect.generated.resources.pairing_nearby_section
import org.jetbrains.compose.resources.stringResource

/**
 * The hosts advertising an open pairing window, as screen 02 lays them out: the name the host drew for this
 * window, its derived id beneath, and nothing else — a host says what project it has only after approval.
 */
@Composable
internal fun PairingHostSection(
    hosts: List<PairingHostUiState>,
    onHostSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = stringResource(Res.string.pairing_nearby_section),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.small),
        )
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(Res.string.pairing_nearby_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.small),
            )
        }
        val blockedLabel = stringResource(Res.string.pairing_host_blocked)
        // Keyed by the derived id: rows come and go every second as windows open and close.
        hosts.forEach { host ->
            key(host.shortId) {
                // Starts hidden, so a host that has just opened its window slides in instead of popping up.
                val appearance = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = appearance, enter = fadeIn() + expandVertically()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = Corners.lg,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(Borders.regular, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        TennyoListRow(
                            title = host.hostName,
                            subtitle = if (host.isBlocked) blockedLabel else host.displayShortId,
                            enabled = !host.isBlocked,
                            onClick = { onHostSelected(host.shortId) },
                            leading = {
                                // A round accent tile, as the design marks a host that is open to pair.
                                Surface(
                                    modifier = Modifier.size(ComponentSizes.iconButton),
                                    shape = Corners.full,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = TennyoIcons.Wifi,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(ComponentSizes.compactIcon),
                                        )
                                    }
                                }
                            },
                            trailing = {
                                Icon(
                                    imageVector = TennyoIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The help card screen 02 ends on: why a host might not be listed, without promising a fix. */
@Composable
internal fun PairingHelpCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.lg,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = TennyoIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Text(
                    text = stringResource(Res.string.pairing_help_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.pairing_help_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
