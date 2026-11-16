package app.tennyo.mobile.feature.git

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.tennyo.mobile.design.TennyoDropdownMenu
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoSegmentedTabs
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_changes
import app.tennyo.mobile.feature.git.generated.resources.git_detached_head
import app.tennyo.mobile.feature.git.generated.resources.git_fetch
import app.tennyo.mobile.feature.git.generated.resources.git_history
import app.tennyo.mobile.feature.git.generated.resources.git_no_repository
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_cancelled_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_cancelled_title
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_completed_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_completed_title
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_failed_title
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_unknown_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_unknown_title
import app.tennyo.mobile.feature.git.generated.resources.git_project
import app.tennyo.mobile.feature.git.generated.resources.git_pull
import app.tennyo.mobile.feature.git.generated.resources.git_push
import app.tennyo.mobile.feature.git.generated.resources.git_refreshing
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GitRepositoryMissingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(Res.string.git_no_repository),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the last mutation of the repository came to, between the branch header and the sections. A finished outcome
 * can be dismissed (success also leaves by itself); an unknown one stays, because it is what keeps further mutations
 * blocked until a status says what happened.
 */
@Composable
internal fun GitMutationOutcomeBanner(
    outcome: GitMutationOutcome?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = outcome,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(AnimationDurations.regularMillis)) togetherWith fadeOut(tween(AnimationDurations.quickMillis))
        },
        contentKey = { it != null },
        label = "Git outcome",
    ) { shown ->
        shown?.let { GitOutcomeNotice(it, onDismiss, Modifier.padding(bottom = Spacing.small)) }
    }
}

@Composable
private fun GitOutcomeNotice(outcome: GitMutationOutcome, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val received = (outcome as? GitMutationOutcome.Received)?.outcome
    val title = when (received) {
        null -> Res.string.git_outcome_unknown_title
        GitOutcome.SUCCESS -> Res.string.git_outcome_completed_title
        GitOutcome.CANCELLED -> Res.string.git_outcome_cancelled_title
        else -> Res.string.git_outcome_failed_title
    }
    val message = when (received) {
        null -> stringResource(Res.string.git_outcome_unknown_message)
        GitOutcome.SUCCESS -> stringResource(Res.string.git_outcome_completed_message)
        GitOutcome.CANCELLED -> stringResource(Res.string.git_outcome_cancelled_message)
        else -> received.message.resolve()
    }
    TennyoStatusBanner(
        title = stringResource(title),
        message = message,
        tone = when (received) {
            GitOutcome.SUCCESS -> TennyoStatusTone.Connected
            null, GitOutcome.CANCELLED -> TennyoStatusTone.Warning
            else -> TennyoStatusTone.Failed
        },
        icon = if (received == GitOutcome.SUCCESS) TennyoIcons.Check else TennyoIcons.ErrorOutline,
        onDismiss = onDismiss.takeIf { received != null },
        modifier = modifier,
    )
}

/**
 * Changes / History (and Project, where the source offers it), as a segmented control inside the destination
 * (screen G1); the branch list opens from the branch bar instead. They are sections of one place, not open editors,
 * so they read as a segmented control rather than an editor tab strip.
 */
@Composable
internal fun GitSectionTabs(
    sections: List<GitSection>,
    selectedSection: GitSection,
    changeCount: Int,
    onSectionSelected: (GitSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoSegmentedTabs(
        items = sections.map { section ->
            when (section) {
                GitSection.Changes ->
                    if (changeCount > 0) {
                        "${stringResource(Res.string.git_changes)} $changeCount"
                    } else {
                        stringResource(Res.string.git_changes)
                    }

                GitSection.History -> stringResource(Res.string.git_history)
                GitSection.Project -> stringResource(Res.string.git_project)
            }
        },
        selectedIndex = sections.indexOf(selectedSection),
        onSelected = { index -> onSectionSelected(sections[index]) },
        modifier = modifier.fillMaxWidth(),
        itemKeys = sections,
    )
}

@Composable
internal fun GitRepositoryChooser(
    repositories: List<GitRepository>,
    selectedRepositoryId: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = repositories.indexOfFirst { it.id == selectedRepositoryId }.coerceAtLeast(0)
    var expanded by remember { mutableStateOf(false) }
    TennyoDropdownMenu(
        selectedLabel = repositories[selectedIndex].rootName,
        options = repositories.map(GitRepository::rootName),
        selectedIndex = selectedIndex,
        onSelected = { index ->
            expanded = false
            onSelected(repositories[index].id)
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        enabled = enabled,
        fillAnchor = true,
        modifier = modifier.fillMaxWidth(),
    )
}
