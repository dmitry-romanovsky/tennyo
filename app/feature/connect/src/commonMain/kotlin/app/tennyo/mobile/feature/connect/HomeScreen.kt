package app.tennyo.mobile.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.contract.PairingWords
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoOrbitMark
import app.tennyo.mobile.design.layout.isScreenLandscape
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.layout.safeDrawingHorizontalPadding
import app.tennyo.mobile.design.layout.safeDrawingTopPadding
import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.app_name
import app.tennyo.mobile.feature.connect.generated.resources.main_action_connect_to_remote
import app.tennyo.mobile.feature.connect.generated.resources.main_action_end_session
import app.tennyo.mobile.feature.connect.generated.resources.main_action_resume
import app.tennyo.mobile.feature.connect.generated.resources.main_onboarding_description
import app.tennyo.mobile.feature.connect.generated.resources.main_project_list_description
import app.tennyo.mobile.feature.connect.generated.resources.main_session_hours
import app.tennyo.mobile.feature.connect.generated.resources.main_session_just_started
import app.tennyo.mobile.feature.connect.generated.resources.main_session_minutes
import app.tennyo.mobile.feature.connect.generated.resources.main_session_summary
import app.tennyo.mobile.feature.connect.generated.resources.main_settings
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_pairing_body
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_pairing_title
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_plugin_body
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_plugin_title
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_words_body
import app.tennyo.mobile.feature.connect.generated.resources.welcome_step_words_title
import app.tennyo.mobile.feature.connect.generated.resources.welcome_subtitle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Home of [component]; [local] is null where the platform has no on-device workspaces: the remote flow only. */
@Composable
fun HomeScreen(
    component: HomeComponent,
    local: HomeLocalContent?,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    HomeScreen(
        state = state,
        onIntent = component::onIntent,
        local = local,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    local: HomeLocalContent?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ConnectFab(
                activeSession = state.activeSession,
                onClick = { onIntent(HomeIntent.OpenConnect) },
                onEndSession = { onIntent(HomeIntent.EndSession) },
                modifier = Modifier.padding(bottom = safeDrawingBottomInset()),
            )
        },
    ) { innerPadding ->
        val onSettings = { onIntent(HomeIntent.OpenSettings) }
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .safeDrawingTopPadding()
            .safeDrawingHorizontalPadding()
            .padding(horizontal = Spacing.extraLarge)
        when {
            local == null -> RemoteOnlyContent(onSettings, contentModifier)
            local.hasWorkspaces && isScreenLandscape() -> Row(
                modifier = contentModifier.padding(top = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
            ) {
                Column(
                    modifier = Modifier.weight(LANDSCAPE_ACTIONS_WEIGHT),
                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                ) {
                    HomeHeader(hasProjects = true, onSettings = onSettings)
                    local.actions(Modifier)
                }
                local.workspaces(Modifier.weight(LANDSCAPE_WORKSPACES_WEIGHT))
            }

            local.hasWorkspaces -> Column(modifier = contentModifier.padding(top = Spacing.small)) {
                HomeHeader(hasProjects = true, onSettings = onSettings)
                Spacer(modifier = Modifier.height(Spacing.extraLarge))
                local.actions(Modifier)
                Spacer(modifier = Modifier.height(Spacing.extraLarge))
                local.workspaces(Modifier.weight(1f))
            }

            else -> Column(modifier = contentModifier.padding(top = Spacing.small)) {
                HomeHeader(hasProjects = false, onSettings = onSettings)
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
                    ) {
                        local.actions(Modifier.padding(bottom = Spacing.medium))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteOnlyContent(
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = Spacing.small)) {
        HomeHeader(hasProjects = false, onSettings = onSettings)
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
            ) {
                RemoteConnectionGuide(Modifier.weight(1f).padding(top = Spacing.extraLarge))
            }
        }
    }
}

/**
 * Heads Home in place of a top bar: the mark turning slowly beside the name, so the first screen reads as Tennyo's own,
 * with Settings level with them at a full tap size.
 */
@Composable
private fun HomeHeader(
    hasProjects: Boolean,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TennyoOrbitMark(markSize = ComponentSizes.homeMark, orbitMillis = HOME_ORBIT_MILLIS)
            Text(
                text = stringResource(Res.string.app_name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TennyoIconButton(
                icon = TennyoIcons.Settings,
                contentDescription = stringResource(Res.string.main_settings),
                onClick = onSettings,
                size = ComponentSizes.touchTarget,
                iconSize = ComponentSizes.icon,
                contentColor = MaterialTheme.colorScheme.onSurface,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Text(
            text = if (hasProjects) {
                stringResource(Res.string.main_project_list_description)
            } else {
                stringResource(Res.string.main_onboarding_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemoteConnectionGuide(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = stringResource(Res.string.welcome_subtitle),
            modifier = Modifier.padding(bottom = Spacing.large),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RemoteConnectionGuideStep(
            ordinal = 1,
            title = stringResource(Res.string.welcome_step_plugin_title),
            body = stringResource(Res.string.welcome_step_plugin_body),
        )
        RemoteConnectionGuideStep(
            ordinal = 2,
            title = stringResource(Res.string.welcome_step_pairing_title),
            body = stringResource(Res.string.welcome_step_pairing_body),
        )
        RemoteConnectionGuideStep(
            ordinal = 3,
            title = stringResource(Res.string.welcome_step_words_title),
            // The design says "three words"; PairingWords.SECRET_LENGTH is what the host actually shows.
            body = stringResource(Res.string.welcome_step_words_body, PairingWords.SECRET_LENGTH),
            showDivider = false,
        )
    }
}

@Composable
private fun RemoteConnectionGuideStep(
    ordinal: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Surface(
                modifier = Modifier.size(ComponentSizes.remoteConnectionGuideIconContainer),
                shape = Corners.md,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = ordinal.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = ComponentSizes.remoteConnectionGuideIconContainer + Spacing.medium,
                    top = Spacing.medium,
                ),
                thickness = Borders.regular,
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * The one way into a computer: a large button in the thumb's corner, on every layout of Home. With a session running
 * it resumes that session — naming the computer and how long it has run — and a stop button beside it ends it.
 */
@Composable
private fun ConnectFab(
    activeSession: ActiveLinkSummary?,
    onClick: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activeSession != null) {
            FilledTonalIconButton(
                onClick = onEndSession,
                modifier = Modifier.size(ComponentSizes.largeFab),
                shape = Corners.xl,
            ) {
                Icon(
                    imageVector = TennyoIcons.Stop,
                    contentDescription = stringResource(Res.string.main_action_end_session),
                    modifier = Modifier.size(ComponentSizes.icon),
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = ComponentSizes.largeFab),
            shape = Corners.xl,
            icon = {
                Icon(
                    imageVector = if (activeSession != null) TennyoIcons.PlayArrow else TennyoIcons.Link,
                    contentDescription = null,
                    modifier = Modifier.size(ComponentSizes.icon),
                )
            },
            text = {
                if (activeSession == null) {
                    FabTitle(stringResource(Res.string.main_action_connect_to_remote))
                } else {
                    Column {
                        FabTitle(stringResource(Res.string.main_action_resume))
                        Text(
                            text = stringResource(
                                Res.string.main_session_summary,
                                activeSession.hostLabel,
                                sessionDuration(activeSession.startedAtEpochMillis),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun FabTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** How long the session has run, re-read every minute while Home shows it. */
@Composable
private fun sessionDuration(startedAtEpochMillis: Long): String {
    val minutes by produceState(elapsedMinutes(startedAtEpochMillis), startedAtEpochMillis) {
        while (true) {
            value = elapsedMinutes(startedAtEpochMillis)
            delay(MINUTE_MILLIS.milliseconds)
        }
    }
    return when {
        minutes < 1 -> stringResource(Res.string.main_session_just_started)
        minutes < MINUTES_PER_HOUR -> stringResource(Res.string.main_session_minutes, minutes)
        else -> stringResource(Res.string.main_session_hours, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }
}

private fun elapsedMinutes(startedAtEpochMillis: Long): Int =
    ((Clock.System.now().toEpochMilliseconds() - startedAtEpochMillis) / MINUTE_MILLIS).coerceAtLeast(0).toInt()

private const val MINUTE_MILLIS = 60_000L
private const val MINUTES_PER_HOUR = 60

/** One turn of the Home mark: slow enough to read as calm, where the busy indicator's turn reads as work. */
private const val HOME_ORBIT_MILLIS = 6_000

private const val LANDSCAPE_ACTIONS_WEIGHT = 0.9f
private const val LANDSCAPE_WORKSPACES_WEIGHT = 1.1f

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomeRemoteOnlyPreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark, colorScheme = AppColorScheme.TennyoTheme) {
        HomeScreen(state = HomeState(), onIntent = {}, local = null)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomeResumePreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark, colorScheme = AppColorScheme.TennyoTheme) {
        HomeScreen(
            state = HomeState(activeSession = ActiveLinkSummary("Cursor 1.6", startedAtEpochMillis = 0L)),
            onIntent = {},
            local = null,
        )
    }
}
