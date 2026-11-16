package app.tennyo.mobile.feature.local

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** The workspaces on this device, most recently created first, and the editor tabs each had open. */
interface LocalWorkspaceStore {
    val workspaces: Flow<List<LocalWorkspace>>

    suspend fun get(slug: String): LocalWorkspace?

    /** Records a new workspace called [name] under a free slug and creates its (empty) directory. */
    suspend fun reserve(name: String): LocalWorkspace

    suspend fun updateTabs(slug: String, openedTabs: List<String>, selectedTab: Int)

    /** Forgets the workspace and deletes its files. */
    suspend fun remove(slug: String)
}

/**
 * [LocalWorkspaceStore] as one JSON index file. Every write goes to a temporary file that then replaces the index, so a
 * crash leaves either the old or the new index. An index that cannot be read is rebuilt from the workspace directories,
 * and entries whose directory no longer exists are dropped on load.
 */
class JsonLocalWorkspaceStore(
    private val fileSystem: FileSystem,
    private val indexFile: Path,
    private val files: WorkspaceFiles,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : LocalWorkspaceStore {
    private val mutex = Mutex()
    private val index = MutableStateFlow<List<LocalWorkspace>?>(null)

    override val workspaces: Flow<List<LocalWorkspace>> = flow {
        loaded()
        emitAll(index.map { it.orEmpty().sortedByDescending(LocalWorkspace::createdAtEpochMillis) })
    }

    override suspend fun get(slug: String): LocalWorkspace? = loaded().firstOrNull { it.slug == slug }

    override suspend fun reserve(name: String): LocalWorkspace = mutex.withLock {
        val current = loadedLocked()
        val taken = current.mapTo(mutableSetOf(), LocalWorkspace::slug) + existingDirectories()
        val workspace = LocalWorkspace(name.trim(), workspaceSlug(name, taken), createdAtEpochMillis = now())
        files.create(workspace.slug)
        writeLocked(current + workspace)
        workspace
    }

    override suspend fun updateTabs(slug: String, openedTabs: List<String>, selectedTab: Int) = mutex.withLock {
        val current = loadedLocked()
        if (current.none { it.slug == slug }) return@withLock
        writeLocked(
            current.map { if (it.slug == slug) it.copy(openedTabs = openedTabs, selectedTab = selectedTab) else it },
        )
    }

    // The index forgets the workspace before its files go, and the deletion outlives a cancelled caller: an
    // interrupted delete leaves an unlisted directory, never a listed workspace with half its files.
    override suspend fun remove(slug: String) = withContext(NonCancellable) {
        mutex.withLock {
            writeLocked(loadedLocked().filterNot { it.slug == slug })
            files.delete(slug)
        }
    }

    private suspend fun loaded(): List<LocalWorkspace> = index.value ?: mutex.withLock { loadedLocked() }

    private suspend fun loadedLocked(): List<LocalWorkspace> = index.value ?: withContext(ioContext) {
        val stored = readIndex()
        // A workspace whose directory is gone (a clone that failed in the background) is forgotten.
        val workspaces = stored?.filter { fileSystem.exists(files.workspacesRoot / it.slug) }
            ?: recoverFromDirectories()
        if (stored != workspaces) writeIndex(workspaces)
        workspaces
    }.also { index.value = it }

    private suspend fun writeLocked(workspaces: List<LocalWorkspace>) {
        withContext(ioContext) { writeIndex(workspaces) }
        index.value = workspaces
    }

    /** The stored index, or `null` when there is none or it cannot be decoded. */
    private fun readIndex(): List<LocalWorkspace>? {
        if (!fileSystem.exists(indexFile)) return null
        return try {
            IndexJson.decodeFromString(WorkspaceIndex.serializer(), fileSystem.read(indexFile) { readUtf8() })
                .workspaces
        } catch (failure: SerializationException) {
            Logger.w(failure) { "Workspace index is unreadable; rebuilding it from the workspace directories" }
            null
        } catch (failure: IllegalArgumentException) {
            Logger.w(failure) { "Workspace index is malformed; rebuilding it from the workspace directories" }
            null
        } catch (failure: IOException) {
            Logger.w(failure) { "Workspace index could not be read; rebuilding it from the workspace directories" }
            null
        }
    }

    private fun recoverFromDirectories(): List<LocalWorkspace> = existingDirectories().map { slug ->
        LocalWorkspace(name = slug, slug = slug, createdAtEpochMillis = now())
    }

    private fun existingDirectories(): List<String> =
        fileSystem.listOrNull(files.workspacesRoot).orEmpty()
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .map { it.name }

    private fun writeIndex(workspaces: List<LocalWorkspace>) {
        indexFile.parent?.let(fileSystem::createDirectories)
        val temporary = indexFile.parent?.div("${indexFile.name}.tmp")
            ?: throw WorkspaceFault.Unwritable(indexFile.name)
        try {
            fileSystem.write(temporary) {
                writeUtf8(IndexJson.encodeToString(WorkspaceIndex.serializer(), WorkspaceIndex(workspaces)))
            }
            fileSystem.atomicMove(temporary, indexFile)
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(indexFile.name)
        }
    }
}

@Serializable
private data class WorkspaceIndex(val workspaces: List<LocalWorkspace>)

private val IndexJson = Json { ignoreUnknownKeys = true }
