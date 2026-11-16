package app.tennyo.mobile.feature.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoStatusChip
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.connect_error_details
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_action
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_addresses
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_connecting
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_forget
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_last_used_days
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_last_used_hours
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_last_used_minutes
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_last_used_now
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_local_network
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_reach_away
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_reach_nearby
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_section
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_status_available
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_status_blocked
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_status_checking
import app.tennyo.mobile.feature.connect.generated.resources.connect_known_host_status_unavailable
import app.tennyo.mobile.link.pairing.KnownHostReach
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes


@Composable
internal fun ConnectSecondaryBlock(
    state: ConnectState,
    onShowErrorDetails: (String) -> Unit,
    onForgetKnownHost: (String) -> Unit,
    onConnectKnownHost: (String) -> Unit,
    onShowAddresses: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var removal by remember { mutableStateOf<KnownHostRemoval?>(null) }
    var removalVisible by remember { mutableStateOf(false) }
    val displayedTargets = remember(state.knownHostTargets, removal) {
        buildList {
            val currentRemoval = removal
            addAll(state.knownHostTargets.filterNot { it.companionId == currentRemoval?.target?.companionId })
            currentRemoval?.let { add(it.index.coerceAtMost(size), it.target) }
        }
    }

    LaunchedEffect(removal) {
        if (removal != null) {
            removalVisible = false
            delay(AnimationDurations.regularMillis.milliseconds)
            removal = null
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        var visibleDiscoveryError by remember { mutableStateOf<ConnectErrorUiState?>(null) }
        LaunchedEffect(state.discoveryError) {
            state.discoveryError?.let { error ->
                visibleDiscoveryError = error
            }
        }
        AnimatedVisibility(
            visible = state.discoveryError != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val error = visibleDiscoveryError ?: return@AnimatedVisibility
            val details = error.details?.resolve()
            ConnectionErrorCard(
                error = error,
                onShowDetails = details?.let { { onShowErrorDetails(it) } },
            )
        }
        if (displayedTargets.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                // No refresh control: PairingDiscoveryLoop keeps this list current while the screen is on screen.
                Text(
                    text = stringResource(Res.string.connect_known_host_section),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.small),
                )
                displayedTargets.forEachIndexed { index, target ->
                    key(target.companionId) {
                        val isRemoving = target.companionId == removal?.target?.companionId
                        AnimatedVisibility(visible = !isRemoving || removalVisible) {
                            KnownHostRow(
                                target = target,
                                isStarting = state.selectedDiscoveryTargetId == target.companionId,
                                onForget = {
                                    if (removal == null) {
                                        removal = KnownHostRemoval(target, index)
                                        removalVisible = true
                                        onForgetKnownHost(target.companionId)
                                    }
                                },
                                onClick = { onConnectKnownHost(target.companionId) },
                                onAddresses = { onShowAddresses(target.companionId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class KnownHostRemoval(
    val target: KnownHostTargetUiState,
    val index: Int,
)


/** A failed remembered-host attempt, with the host's or the transport's own account behind "Details". */
@Composable
internal fun ConnectionErrorCard(
    error: ConnectErrorUiState,
    onShowDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.md,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(error.failure.text),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            if (onShowDetails != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TennyoButton(
                        label = stringResource(Res.string.connect_error_details),
                        onClick = onShowDetails,
                        style = TennyoButtonStyle.Text,
                        size = TennyoButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
internal fun KnownHostRow(
    target: KnownHostTargetUiState,
    isStarting: Boolean,
    onForget: () -> Unit,
    onClick: () -> Unit,
    onAddresses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = Corners.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Borders.regular, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(ComponentSizes.iconButton),
                    shape = Corners.sm,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = target.companionLabel
                                .ifBlank { target.lastProjectName ?: "I" }
                                .first()
                                .uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = target.companionLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                        Spacer(modifier = Modifier.size(Spacing.extraSmall))
                        TennyoStatusChip(
                            label = target.availability.label(),
                            tone = target.availability.tone(),
                            showDot = true,
                        )
                    }
                    Text(
                        text = target.displayShortId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    target.reach?.let { reach ->
                        Text(
                            text = reach.label(target.resolvedAddress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.size(Spacing.extraSmall))
                    val networkLabel = target.networkLabel
                        ?: stringResource(Res.string.connect_known_host_local_network)
                    val secondaryText = buildList {
                        listOf(target.hostVersion, networkLabel)
                            .filter { it.isNotBlank() }
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(" • ")
                            ?.let(::add)
                        add(knownHostLastUsedText(target.lastUsedAtEpochMillis))
                    }.joinToString("\n")
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TennyoButton(
                    label = stringResource(Res.string.connect_known_host_forget),
                    onClick = onForget,
                    enabled = !isStarting,
                    style = TennyoButtonStyle.Danger,
                    size = TennyoButtonSize.PrimaryAction,
                )
                TennyoButton(
                    label = stringResource(Res.string.connect_known_host_addresses),
                    onClick = onAddresses,
                    enabled = !isStarting,
                    style = TennyoButtonStyle.Text,
                    size = TennyoButtonSize.PrimaryAction,
                )
                Spacer(Modifier.weight(1f).widthIn(Spacing.medium))
                AnimatedVisibility(
                    visible = target.availability == KnownHostAvailability.AVAILABLE || isStarting,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    TennyoButton(
                        label = if (isStarting) {
                            stringResource(Res.string.connect_known_host_connecting)
                        } else {
                            stringResource(Res.string.connect_known_host_action)
                        },
                        onClick = onClick,
                        enabled = target.actionEnabled && !isStarting,
                        size = TennyoButtonSize.PrimaryAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun KnownHostReach.label(address: String?): String =
    when (this) {
        KnownHostReach.NEARBY -> stringResource(Res.string.connect_known_host_reach_nearby)
        KnownHostReach.AWAY -> stringResource(Res.string.connect_known_host_reach_away, address.orEmpty())
    }

@Composable
private fun KnownHostAvailability.tone(): TennyoStatusTone =
    when (this) {
        KnownHostAvailability.AVAILABLE -> TennyoStatusTone.Connected
        KnownHostAvailability.BLOCKED -> TennyoStatusTone.Failed
        KnownHostAvailability.UNKNOWN -> TennyoStatusTone.Reconnecting
        KnownHostAvailability.UNAVAILABLE -> TennyoStatusTone.Neutral
    }

@Composable
private fun KnownHostAvailability.label(): String =
    when (this) {
        KnownHostAvailability.AVAILABLE -> stringResource(Res.string.connect_known_host_status_available)
        KnownHostAvailability.UNAVAILABLE -> stringResource(Res.string.connect_known_host_status_unavailable)
        KnownHostAvailability.BLOCKED -> stringResource(Res.string.connect_known_host_status_blocked)
        KnownHostAvailability.UNKNOWN -> stringResource(Res.string.connect_known_host_status_checking)
    }

/** How long ago a remembered host was last connected, to the nearest minute, hour, or day. */
@Composable
internal fun knownHostLastUsedText(lastUsedAtEpochMillis: Long): String {
    val age = (Clock.System.now().toEpochMilliseconds() - lastUsedAtEpochMillis).coerceAtLeast(0L).milliseconds
    return when {
        age < 1.minutes -> stringResource(Res.string.connect_known_host_last_used_now)
        age < 1.hours -> stringResource(Res.string.connect_known_host_last_used_minutes, age.inWholeMinutes)
        age < 1.days -> stringResource(Res.string.connect_known_host_last_used_hours, age.inWholeHours)
        else -> stringResource(Res.string.connect_known_host_last_used_days, age.inWholeDays)
    }
}
