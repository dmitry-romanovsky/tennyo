package app.tennyo.mobile.design.files

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme

data class ProjectFilesScreenState(
    val title: String,
    val breadcrumbs: List<ProjectFilesBreadcrumb>,
    val emptyText: String,
    val parentId: String? = null,
    val entries: List<ProjectFilesEntry> = emptyList(),
    val selectedId: String? = null,
    val loading: Boolean = false,
)

data class ProjectFilesBreadcrumb(
    val id: String,
    val name: String,
)

data class ProjectFilesEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
)

/** Insets the file navigator keeps clear; zero where the platform's sheet already avoids the system bars. */
@Composable
internal expect fun fileNavigatorTopInset(): Dp

@Composable
internal expect fun fileNavigatorBottomInset(): Dp

@Composable
fun ProjectFilesScreen(
    state: ProjectFilesScreenState,
    onDirectoryClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(top = fileNavigatorTopInset())) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(Spacing.large),
        )
        BreadcrumbRow(
            breadcrumbs = state.breadcrumbs,
            onDirectoryClick = onDirectoryClick,
            modifier = Modifier.padding(bottom = Spacing.extraSmall),
        )
        HorizontalDivider(thickness = Borders.thin)
        Column(Modifier.padding(horizontal = Spacing.extraLarge)) {
            state.parentId?.let { parentId ->
                ParentDirectoryRow(onClick = { onDirectoryClick(parentId) })
            }
            DirectoryEntries(
                state = state,
                onDirectoryClick = onDirectoryClick,
                onFileClick = onFileClick,
            )
        }
    }
}

@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<ProjectFilesBreadcrumb>,
    onDirectoryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    KeepScrolledToEnd(scrollState)
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .animateContentSize(tween(AnimationDurations.regularMillis))
            .padding(horizontal = Spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            Icon(
                imageVector = if (index == 0) TennyoIcons.Folder else TennyoIcons.ChevronRight,
                contentDescription = null,
            )
            TennyoButton(
                label = breadcrumb.name,
                onClick = { onDirectoryClick(breadcrumb.id) },
                style = TennyoButtonStyle.Text,
                size = TennyoButtonSize.Small,
            )
        }
    }
}

/** Follows the trail's end while the row grows or shrinks, so the current folder stays in view. */
@Composable
private fun KeepScrolledToEnd(scrollState: ScrollState) {
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
}

@Composable
private fun ParentDirectoryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TennyoListRow(
            title = "..",
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            leading = {
                Icon(
                    TennyoIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(ComponentSizes.compactIcon),
                )
            },
        )
        HorizontalDivider(thickness = Borders.regular)
    }
}

@Composable
private fun DirectoryEntries(
    state: ProjectFilesScreenState,
    onDirectoryClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = fileNavigatorBottomInset()
    Crossfade(targetState = state.loading, label = "Directory loading", modifier = modifier) { loading ->
        when {
            loading -> Box(Modifier.fillMaxSize()) {
                TennyoProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.entries.isEmpty() -> Box(Modifier.fillMaxSize()) {
                Text(state.emptyText, Modifier.align(Alignment.Center))
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = Spacing.small + bottomInset)) {
                items(state.entries, key = ProjectFilesEntry::id) { entry ->
                    ProjectEntryRow(
                        entry = entry,
                        selected = entry.id == state.selectedId,
                        onClick = {
                            if (entry.isDirectory) onDirectoryClick(entry.id) else onFileClick(entry.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectEntryRow(
    entry: ProjectFilesEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoListRow(
        title = entry.name,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leading = {
            Icon(
                imageVector = if (entry.isDirectory) TennyoIcons.FolderOpen else TennyoIcons.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ComponentSizes.compactIcon),
            )
        },
    )
}

@Preview(widthDp = 420, heightDp = 600, showBackground = true)
@Composable
private fun ProjectFilesScreenPreview() {
    TennyoTheme(themeMode = AppThemeMode.Light) {
        ProjectFilesScreen(
            state = ProjectFilesScreenState(
                title = "Files",
                breadcrumbs = listOf(
                    ProjectFilesBreadcrumb("", "Project"),
                    ProjectFilesBreadcrumb("src", "src"),
                    ProjectFilesBreadcrumb("src/main", "main"),
                ),
                emptyText = "No files",
                parentId = "src",
                entries = listOf(
                    ProjectFilesEntry("src/main/kotlin", "kotlin", isDirectory = true),
                    ProjectFilesEntry("src/main/resources", "resources", isDirectory = true),
                    ProjectFilesEntry("src/main/kotlin/App.kt", "App.kt", isDirectory = false),
                ),
                selectedId = "src/main/kotlin/App.kt",
            ),
            onDirectoryClick = {},
            onFileClick = {},
        )
    }
}
