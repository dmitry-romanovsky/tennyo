package app.tennyo.mobile.feature.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.tennyo.contract.FileNode
import app.tennyo.mobile.design.files.ProjectFilesBreadcrumb
import app.tennyo.mobile.design.files.ProjectFilesEntry
import app.tennyo.mobile.design.files.ProjectFilesScreen
import app.tennyo.mobile.design.files.ProjectFilesScreenState
import app.tennyo.mobile.feature.files.generated.resources.Res
import app.tennyo.mobile.feature.files.generated.resources.files_empty_folder
import app.tennyo.mobile.feature.files.generated.resources.files_empty_loaded
import app.tennyo.mobile.feature.files.generated.resources.files_project_root
import app.tennyo.mobile.feature.files.generated.resources.files_title
import org.jetbrains.compose.resources.stringResource

/** The project tree drawer of the files pane: browsing loads folders lazily, choosing a file opens it in a tab. */
@Composable
fun FilesNavigator(
    component: FilesPaneComponent,
    onFileOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    var currentFolderId by remember(state.root?.path) { mutableStateOf(state.root?.path.orEmpty()) }
    val labels = FilesLabels(
        filesTitle = stringResource(Res.string.files_title),
        projectRoot = stringResource(Res.string.files_project_root),
        emptyLoaded = stringResource(Res.string.files_empty_loaded),
        emptyFolder = stringResource(Res.string.files_empty_folder),
    )
    val selectedFilePath = state.selectedFile?.path
    val screenState = remember(state.root, currentFolderId, selectedFilePath, labels) {
        state.root.toProjectFilesScreenState(currentFolderId, selectedFilePath, labels)
    }
    ProjectFilesScreen(
        state = screenState,
        onDirectoryClick = { folderId ->
            currentFolderId = folderId
            component.onIntent(FilesIntent.LoadFolder(folderId))
        },
        onFileClick = { path ->
            component.onIntent(FilesIntent.OpenFile(path))
            onFileOpened()
        },
        modifier = modifier,
    )
}

internal data class FilesLabels(
    val filesTitle: String,
    val projectRoot: String,
    val emptyLoaded: String,
    val emptyFolder: String,
)

internal fun FileNode?.toProjectFilesScreenState(
    currentFolderId: String,
    selectedId: String?,
    labels: FilesLabels,
): ProjectFilesScreenState {
    val root = this ?: return ProjectFilesScreenState(
        title = labels.filesTitle,
        breadcrumbs = listOf(ProjectFilesBreadcrumb("", labels.filesTitle)),
        loading = false,
        emptyText = labels.emptyLoaded,
    )
    val currentFolder = root.findDirectory(currentFolderId) ?: root
    val currentTrail = root.directoryTrail(currentFolder.path)
    return ProjectFilesScreenState(
        title = labels.filesTitle,
        breadcrumbs = root.breadcrumbsFor(currentFolder.path, labels),
        parentId = currentTrail.parentDirectoryId(),
        entries = currentFolder.children
            .sortedBy { it.name.lowercase() }
            .sortedByDescending { it.isDirectory }
            .map { child -> ProjectFilesEntry(id = child.path, name = child.name, isDirectory = child.isDirectory) },
        selectedId = selectedId,
        loading = false,
        emptyText = labels.emptyFolder,
    )
}

private fun FileNode.findDirectory(path: String): FileNode? {
    if (this.path == path && isDirectory) return this
    return children.firstNotNullOfOrNull { it.findDirectory(path) }
}

private fun FileNode.breadcrumbsFor(path: String, labels: FilesLabels): List<ProjectFilesBreadcrumb> {
    val trail = directoryTrail(path)
    if (trail != null) {
        return trail.map { node -> ProjectFilesBreadcrumb(node.path, node.name.ifBlank { labels.projectRoot }) }
    }
    val breadcrumbs = mutableListOf(ProjectFilesBreadcrumb(this.path, name.ifBlank { labels.projectRoot }))
    var currentPath = ""
    path.split('/')
        .filter { it.isNotBlank() }
        .forEach { segment ->
            currentPath = if (currentPath.isBlank()) segment else "$currentPath/$segment"
            breadcrumbs += ProjectFilesBreadcrumb(currentPath, segment)
        }
    return breadcrumbs
}

private fun FileNode.directoryTrail(path: String): List<FileNode>? {
    if (this.path == path && isDirectory) return listOf(this)
    children.forEach { child ->
        val childTrail = child.directoryTrail(path)
        if (childTrail != null) return listOf(this) + childTrail
    }
    return null
}

private fun List<FileNode>?.parentDirectoryId(): String? = this?.takeIf { it.size > 1 }?.dropLast(1)?.lastOrNull()?.path
