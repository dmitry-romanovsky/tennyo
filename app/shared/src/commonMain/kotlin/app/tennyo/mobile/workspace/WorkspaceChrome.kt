package app.tennyo.mobile.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoConnectionIcon
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.generated.resources.Res
import app.tennyo.mobile.generated.resources.banner_close
import app.tennyo.mobile.generated.resources.banner_show_full_message
import app.tennyo.mobile.generated.resources.banner_try_again
import app.tennyo.mobile.generated.resources.connection_reason_connected
import app.tennyo.mobile.generated.resources.connection_reason_connecting
import app.tennyo.mobile.generated.resources.connection_reason_disconnected
import app.tennyo.mobile.generated.resources.connection_reason_failed
import app.tennyo.mobile.generated.resources.connection_reason_new_link_needed
import app.tennyo.mobile.generated.resources.connection_reason_reconnecting
import app.tennyo.mobile.generated.resources.connection_status_connected
import app.tennyo.mobile.generated.resources.connection_status_connecting
import app.tennyo.mobile.generated.resources.connection_status_disconnected
import app.tennyo.mobile.generated.resources.connection_status_failed
import app.tennyo.mobile.generated.resources.connection_status_new_link_needed
import app.tennyo.mobile.generated.resources.connection_status_reconnecting
import app.tennyo.mobile.generated.resources.session_age
import app.tennyo.mobile.generated.resources.session_headline_connected
import app.tennyo.mobile.generated.resources.session_headline_host
import app.tennyo.mobile.generated.resources.stats_download
import app.tennyo.mobile.generated.resources.stats_end_session
import app.tennyo.mobile.generated.resources.stats_group_computer
import app.tennyo.mobile.generated.resources.stats_group_link
import app.tennyo.mobile.generated.resources.stats_group_traffic
import app.tennyo.mobile.generated.resources.stats_host
import app.tennyo.mobile.generated.resources.stats_last_ping
import app.tennyo.mobile.generated.resources.stats_last_ping_value
import app.tennyo.mobile.generated.resources.stats_project
import app.tennyo.mobile.generated.resources.stats_reconnects
import app.tennyo.mobile.generated.resources.stats_total_suffix
import app.tennyo.mobile.generated.resources.stats_transport
import app.tennyo.mobile.generated.resources.stats_unknown
import app.tennyo.mobile.generated.resources.stats_upload
import app.tennyo.mobile.generated.resources.stats_waiting
import app.tennyo.mobile.link.LinkTicket
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun ConnectionBanner(
    state: WorkspaceState,
    onIntent: (WorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onShowFullMessage: () -> Unit,
) {
    val displayConnectionState = state.displayConnectionState()
    val status = displayConnectionState.statusLabel()
    val reason = state.issue?.text?.resolve() ?: displayConnectionState.defaultReason()
    val showBanner = visible &&
            (displayConnectionState != ConnectionState.CONNECTED || state.issue != null) &&
            state.dismissedConnectionMessageKind != state.connectionMessageKind()
    AnimatedVisibility(
        visible = showBanner,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        ConnectionStatusChip(
            state = state,
            displayConnectionState = displayConnectionState,
            status = status,
            reason = reason,
            expanded = false,
            onClose = { onIntent(WorkspaceIntent.DismissConnectionMessage) },
            onRetry = { onIntent(WorkspaceIntent.RetryConnection) },
            onShowFullMessage = onShowFullMessage,
        )
    }
}

@Composable
private fun ConnectionStatusChip(
    state: WorkspaceState,
    displayConnectionState: ConnectionState,
    status: String,
    reason: String,
    expanded: Boolean,
    onClose: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onShowFullMessage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var didOverflow by remember(reason, expanded) { mutableStateOf(false) }
    val showRetry = onRetry != null &&
            state.connectionState in setOf(
        ConnectionState.FAILED,
        ConnectionState.NEEDS_NEW_LINK,
    )
    val contentColor = displayConnectionState.statusContainerContentColor()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .animateContentSize(),
        shape = Corners.xs,
        color = displayConnectionState.statusContainerColor(),
        contentColor = contentColor,
        border = BorderStroke(Borders.regular, contentColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            ConnectionIcon(
                connectionState = displayConnectionState,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .padding(top = ComponentSizes.iconTopAlignmentPadding)
                    .size(ComponentSizes.compactIcon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = ComponentAlphas.visible),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { didOverflow = it.hasVisualOverflow },
                )
                if (!expanded && didOverflow && onShowFullMessage != null) {
                    TennyoButton(
                        label = stringResource(Res.string.banner_show_full_message),
                        onClick = onShowFullMessage,
                        style = TennyoButtonStyle.Secondary,
                        size = TennyoButtonSize.Small,
                    )
                }
                if (showRetry) {
                    TennyoButton(
                        label = stringResource(Res.string.banner_try_again),
                        onClick = onRetry,
                        style = TennyoButtonStyle.Secondary,
                        size = TennyoButtonSize.Small,
                    )
                }
            }
            if (onClose != null) {
                TennyoIconButton(
                    icon = TennyoIcons.Close,
                    contentDescription = stringResource(Res.string.banner_close),
                    onClick = onClose,
                    modifier = Modifier.size(ComponentSizes.iconButton),
                )
            }
        }
    }
}

@Composable
internal fun ConnectionStatusButton(
    state: WorkspaceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayConnectionState = state.displayConnectionState()
    TennyoIconButton(
        icon = displayConnectionState.statusIconVector(),
        contentDescription = displayConnectionState.statusLabel(),
        onClick = onClick,
        modifier = modifier.padding(end = Spacing.small),
    )
}

/**
 * The connection sheet: where the session stands and with which computer, what is wrong and the way out when
 * something is, then the link's detail in grouped rows, and ending the session last.
 */
@Composable
internal fun StatsSheet(
    state: WorkspaceState,
    onIntent: (WorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayConnectionState = state.displayConnectionState()
    val stats = state.stats
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.extraLarge)
            .padding(bottom = Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        SessionHeadline(state = state, displayConnectionState = displayConnectionState)
        // Healthy needs no second word; anything else gets its reason and, where it helps, Retry.
        if (displayConnectionState != ConnectionState.CONNECTED || state.issue != null) {
            ConnectionStatusChip(
                state = state,
                displayConnectionState = displayConnectionState,
                status = displayConnectionState.statusLabel(),
                reason = state.issue?.text?.resolve() ?: displayConnectionState.defaultReason(),
                expanded = true,
                onClose = null,
                onRetry = { onIntent(WorkspaceIntent.RetryConnection) },
                onShowFullMessage = null,
            )
        }
        StatGroup(title = stringResource(Res.string.stats_group_link)) {
            StatRow(stringResource(Res.string.stats_transport), stats.transport)
            StatRow(
                stringResource(Res.string.stats_last_ping),
                stats.lastPingMillis?.let { stringResource(Res.string.stats_last_ping_value, it) }
                    ?: stringResource(Res.string.stats_waiting),
            )
            StatRow(stringResource(Res.string.stats_reconnects), stats.reconnectCount.toString())
        }
        StatGroup(title = stringResource(Res.string.stats_group_traffic)) {
            StatRow(
                stringResource(Res.string.stats_upload),
                stats.uploadSpeedText(),
                stringResource(Res.string.stats_total_suffix, stats.totalUploadText()),
            )
            StatRow(
                stringResource(Res.string.stats_download),
                stats.downloadSpeedText(),
                stringResource(Res.string.stats_total_suffix, stats.totalDownloadText()),
            )
        }
        StatGroup(title = stringResource(Res.string.stats_group_computer)) {
            StatRow(stringResource(Res.string.stats_host), stats.hostName)
            StatRow(stringResource(Res.string.stats_project), state.projectName)
        }
        TennyoButton(
            label = stringResource(Res.string.stats_end_session),
            onClick = { onIntent(WorkspaceIntent.EndSession) },
            style = TennyoButtonStyle.Danger,
            size = TennyoButtonSize.PrimaryAction,
            icon = TennyoIcons.Stop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A titled inset group of rows, divided by hairlines, as a settings list groups related values. */
@Composable
private fun StatGroup(
    title: String,
    modifier: Modifier = Modifier,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.small),
        )
        Surface(
            shape = Corners.md,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                content = rows,
            )
        }
    }
}

/** A label and its value on one line; [detail], when given, sits under the value in the quieter style. */
@Composable
private fun StatRow(
    label: String,
    value: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = value.ifBlank { stringResource(Res.string.stats_unknown) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The block screens 19–22 open with: the state, how long it has been that way, and which computer it is about.
 * Everything here is read from the typed connection state — nothing branches on visible text.
 */
@Composable
private fun SessionHeadline(
    state: WorkspaceState,
    displayConnectionState: ConnectionState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoConnectionIcon(tone = displayConnectionState.tone())
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (displayConnectionState == ConnectionState.CONNECTED) {
                    stringResource(
                        Res.string.session_headline_connected,
                        formatAge(state.stats.sessionStartedAtEpochMillis),
                    )
                } else {
                    displayConnectionState.statusLabel()
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.issue?.text?.resolve() ?: state.hostSubtitle(displayConnectionState),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The computer this session is with, or why there isn't one. */
@Composable
private fun WorkspaceState.hostSubtitle(displayConnectionState: ConnectionState): String {
    val host = stats.hostName.takeIf { it.isNotBlank() }
    val ide = sessionTicket.hostVersion?.takeIf { it.isNotBlank() }
    return when {
        host != null && ide != null -> stringResource(Res.string.session_headline_host, host, ide)
        host != null -> host
        else -> displayConnectionState.defaultReason()
    }
}

@Composable
private fun ConnectionState.tone(): TennyoStatusTone =
    when (this) {
        ConnectionState.CONNECTED -> TennyoStatusTone.Connected
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> TennyoStatusTone.Reconnecting
        ConnectionState.NEEDS_NEW_LINK, ConnectionState.DISCONNECTED -> TennyoStatusTone.Neutral
        ConnectionState.FAILED -> TennyoStatusTone.Failed
    }

@Composable
private fun formatAge(startedAtEpochMillis: Long): String {
    val age = max(0, Clock.System.now().toEpochMilliseconds() - startedAtEpochMillis).milliseconds
    return age.toComponents { minutes, seconds, _ -> stringResource(Res.string.session_age, minutes, seconds) }
}

@Composable
private fun ConnectionState.statusContainerColor(): Color =
    when (this) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.errorContainer.copy(
            alpha = ComponentAlphas.statusContainer,
        )

        ConnectionState.NEEDS_NEW_LINK,
        ConnectionState.FAILED -> MaterialTheme.colorScheme.errorContainer

        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }

@Composable
private fun ConnectionState.statusLabel(): String =
    when (this) {
        ConnectionState.CONNECTED -> stringResource(Res.string.connection_status_connected)
        ConnectionState.CONNECTING -> stringResource(Res.string.connection_status_connecting)
        ConnectionState.RECONNECTING -> stringResource(Res.string.connection_status_reconnecting)
        ConnectionState.NEEDS_NEW_LINK -> stringResource(Res.string.connection_status_new_link_needed)
        ConnectionState.FAILED -> stringResource(Res.string.connection_status_failed)
        ConnectionState.DISCONNECTED -> stringResource(Res.string.connection_status_disconnected)
    }

@Composable
private fun ConnectionState.defaultReason(): String =
    when (this) {
        ConnectionState.CONNECTED -> stringResource(Res.string.connection_reason_connected)
        ConnectionState.CONNECTING -> stringResource(Res.string.connection_reason_connecting)
        ConnectionState.RECONNECTING -> stringResource(Res.string.connection_reason_reconnecting)
        ConnectionState.NEEDS_NEW_LINK -> stringResource(Res.string.connection_reason_new_link_needed)
        ConnectionState.FAILED -> stringResource(Res.string.connection_reason_failed)
        ConnectionState.DISCONNECTED -> stringResource(Res.string.connection_reason_disconnected)
    }

@Composable
private fun ConnectionIcon(
    connectionState: ConnectionState,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (connectionState) {
        ConnectionState.CONNECTED -> Icon(
            imageVector = TennyoIcons.NetworkWifi3Bar,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )

        ConnectionState.DISCONNECTED -> Icon(
            imageVector = TennyoIcons.WifiOff,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )

        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING -> Icon(
            imageVector = TennyoIcons.WifiFind,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )

        ConnectionState.NEEDS_NEW_LINK,
        ConnectionState.FAILED -> Icon(
            imageVector = TennyoIcons.SignalWifiBad,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectionState.statusIconVector(): ImageVector =
    when (this) {
        ConnectionState.CONNECTED -> TennyoIcons.NetworkWifi3Bar
        ConnectionState.DISCONNECTED -> TennyoIcons.WifiOff
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING -> TennyoIcons.WifiFind

        ConnectionState.NEEDS_NEW_LINK,
        ConnectionState.FAILED -> TennyoIcons.SignalWifiBad
    }

@Composable
private fun ConnectionState.statusContainerContentColor(): Color =
    when (this) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        ConnectionState.NEEDS_NEW_LINK,
        ConnectionState.FAILED -> MaterialTheme.colorScheme.onErrorContainer

        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Preview(widthDp = 420)
@Composable
private fun ConnectionBannerDarkPreview() {
    ConnectionBannerPreviewRoot(themeMode = AppThemeMode.Dark)
}

/** The banner in Tennyo's own scheme, where the connected chip's green comes from `tertiary`. */
@Preview(widthDp = 420)
@Composable
private fun ConnectionBannerTennyoThemePreview() {
    ConnectionBannerPreviewRoot(
        themeMode = AppThemeMode.Dark,
        colorScheme = AppColorScheme.TennyoTheme,
    )
}

@Composable
private fun ConnectionBannerPreviewRoot(
    themeMode: AppThemeMode,
    colorScheme: AppColorScheme = AppColorScheme.TennyoTheme,
) {
    TennyoTheme(
        themeMode = themeMode,
        colorScheme = colorScheme,
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.medium)) {
            listOf(
                ConnectionState.RECONNECTING to null,
                ConnectionState.FAILED to WorkspaceIssue.Known.Lost,
            ).forEach { (connectionState, issue) ->
                val state = remember(connectionState, issue) {
                    WorkspaceState(
                        sessionTicket = PREVIEW_TICKET,
                        connectionState = connectionState,
                        projectName = "Tennyo",
                        issue = issue,
                    )
                }
                ConnectionBanner(
                    state = state,
                    onIntent = {},
                    visible = true,
                    onShowFullMessage = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The connection sheet while connected and after the link was lost, in Tennyo's own scheme. */
@Preview(widthDp = 420)
@Composable
private fun StatsSheetPreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark, colorScheme = AppColorScheme.TennyoTheme) {
        Column {
            listOf(ConnectionState.CONNECTED to null, ConnectionState.FAILED to WorkspaceIssue.Known.Lost)
                .forEach { (connectionState, issue) ->
                    StatsSheet(
                        state = WorkspaceState(
                            sessionTicket = PREVIEW_TICKET,
                            connectionState = connectionState,
                            projectName = "Tennyo",
                            issue = issue,
                        ),
                        onIntent = {},
                    )
                }
        }
    }
}

/** A ticket shaped like a real one, for previews only. */
private val PREVIEW_TICKET = LinkTicket(
    endpoint = "https://192.0.2.10:8443",
    sessionId = "preview-session",
    token = "preview-token",
    fingerprint = "preview-fingerprint",
    projectName = "Tennyo",
    projectPath = "~/tennyo",
)
