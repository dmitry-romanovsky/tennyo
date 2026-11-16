package app.tennyo.mobile.feature.git

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_branch_actions
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_ahead
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_behind
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_never_synced
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_synced_hours
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_synced_minutes
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_synced_now
import app.tennyo.mobile.feature.git.generated.resources.git_branch_bar_syncing
import app.tennyo.mobile.feature.git.generated.resources.git_detached_head
import app.tennyo.mobile.feature.git.generated.resources.git_pull
import app.tennyo.mobile.feature.git.generated.resources.git_push
import app.tennyo.mobile.feature.git.generated.resources.git_refreshing
import org.jetbrains.compose.resources.stringResource

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/**
 * The branch bar of screen G1: the current branch, with how far it is from its upstream and how long ago the
 * phone last heard from the host underneath. The whole bar is tappable — it is the way into the branch actions —
 * so the transfer controls beside it stay separate touch targets.
 */
@Composable
internal fun GitBranchBar(
    repository: GitRepository,
    syncAge: GitSyncAge,
    onBranchClick: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onPush: () -> Unit,
    pushEnabled: Boolean,
    modifier: Modifier = Modifier,
    onPull: (() -> Unit)? = null,
    pullEnabled: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.md)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onBranchClick)
                .padding(start = Spacing.medium, end = Spacing.small, top = Spacing.small, bottom = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(
                modifier = Modifier
                    .size(ComponentSizes.iconButton)
                    .clip(Corners.full)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TennyoIcons.CallSplit,
                    contentDescription = stringResource(Res.string.git_branch_actions),
                    modifier = Modifier.size(ComponentSizes.icon),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = repository.currentBranch ?: stringResource(Res.string.git_detached_head),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The bar opens the branch list; the chevron says so without a label.
                    Icon(
                        imageVector = TennyoIcons.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(ComponentSizes.compactIcon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GitBranchSubtitle(repository, syncAge)
            }
        }
        GitBranchBarActions(
            repository = repository,
            onRefresh = onRefresh,
            refreshing = refreshing,
            onPush = onPush,
            pushEnabled = pushEnabled,
            onPull = onPull,
            pullEnabled = pullEnabled,
        )
    }
}

/** "2 ahead · 1 behind · in sync 4m ago": the distance in its own colours, then how fresh this view of it is. */
@Composable
private fun GitBranchSubtitle(repository: GitRepository, syncAge: GitSyncAge, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        val parts = listOfNotNull(
            repository.aheadCount?.takeIf { it > 0 }
                ?.let { stringResource(Res.string.git_branch_bar_ahead, it) to colors.primary },
            repository.behindCount?.takeIf { it > 0 }
                ?.let { stringResource(Res.string.git_branch_bar_behind, it) to colors.onSurface },
            syncAge.label() to colors.onSurfaceVariant,
        )
        parts.forEachIndexed { index, (part, color) ->
            if (index > 0) append(" · ")
            withStyle(SpanStyle(color = color)) { append(part) }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GitBranchBarActions(
    repository: GitRepository,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onPush: () -> Unit,
    pushEnabled: Boolean,
    onPull: (() -> Unit)?,
    pullEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(end = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoIconButton(onClick = onRefresh, enabled = !refreshing) {
            val refreshingDescription = stringResource(Res.string.git_refreshing)
            AnimatedContent(refreshing, Modifier.semantics { contentDescription = refreshingDescription }) {
                if (it) {
                    TennyoProgressIndicator(indicatorSize = ComponentSizes.compactIcon)
                } else {
                    Icon(TennyoIcons.Refresh, contentDescription = null, modifier = Modifier.size(ComponentSizes.icon))
                }
            }
        }
        onPull?.let {
            TennyoIconButton(
                icon = TennyoIcons.ArrowDownward,
                contentDescription = stringResource(Res.string.git_pull),
                onClick = it,
                enabled = pullEnabled,
                iconSize = ComponentSizes.icon,
            )
        }
        if (GitAction.PUSH in repository.availableActions) {
            TennyoIconButton(
                icon = TennyoIcons.ArrowUpward,
                contentDescription = stringResource(Res.string.git_push),
                onClick = onPush,
                enabled = pushEnabled,
                iconSize = ComponentSizes.icon,
            )
        }
    }
}

/**
 * How fresh the phone's view of the repository is. Typed rather than pre-rendered text, so the bar can re-read it
 * as the clock moves without the component pushing a new string.
 */
sealed interface GitSyncAge {
    /** A refresh is in flight. */
    data object Syncing : GitSyncAge

    /** Nothing has arrived yet in this session. */
    data object Never : GitSyncAge

    data class Elapsed(val millis: Long) : GitSyncAge

    companion object {
        fun of(receivedAtEpochMillis: Long, nowEpochMillis: Long, refreshing: Boolean): GitSyncAge = when {
            refreshing -> Syncing
            receivedAtEpochMillis <= 0L -> Never
            else -> Elapsed((nowEpochMillis - receivedAtEpochMillis).coerceAtLeast(0))
        }
    }
}

@Composable
private fun GitSyncAge.label(): String = when (this) {
    GitSyncAge.Syncing -> stringResource(Res.string.git_branch_bar_syncing)
    GitSyncAge.Never -> stringResource(Res.string.git_branch_bar_never_synced)
    is GitSyncAge.Elapsed -> {
        val minutes = millis / MILLIS_PER_MINUTE
        when {
            minutes < 1 -> stringResource(Res.string.git_branch_bar_synced_now)
            minutes < MINUTES_PER_HOUR -> stringResource(Res.string.git_branch_bar_synced_minutes, minutes.toInt())
            else -> stringResource(
                Res.string.git_branch_bar_synced_hours,
                (minutes / MINUTES_PER_HOUR).toInt(),
            )
        }
    }
}
