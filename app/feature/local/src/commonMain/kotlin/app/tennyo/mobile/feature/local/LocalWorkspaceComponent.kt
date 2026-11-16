package app.tennyo.mobile.feature.local

import app.tennyo.mobile.design.AutosaveCountdown
import app.tennyo.mobile.design.PaneNotice
import app.tennyo.mobile.feature.git.GitPaneComponent
import app.tennyo.mobile.feature.git.GitRepositorySource
import app.tennyo.mobile.feature.git.RealGitPaneComponent
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnCreate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** A workspace on this device: its files in an editor, and its repository in the Git pane. */
interface LocalWorkspaceComponent {
    val state: StateFlow<LocalWorkspaceState>
    val effects: Flow<LocalWorkspaceEffect>
    val git: GitPaneComponent

    fun onIntent(intent: LocalWorkspaceIntent)

    fun onBack()
}

sealed interface LocalWorkspaceIntent {
    data class SelectPane(val pane: LocalPane) : LocalWorkspaceIntent
    data class SetNavigatorOpen(val open: Boolean) : LocalWorkspaceIntent
    data class OpenDirectory(val directory: String) : LocalWorkspaceIntent
    data class OpenFile(val path: String) : LocalWorkspaceIntent
    data class CloseFile(val path: String) : LocalWorkspaceIntent
    data class SelectTab(val index: Int) : LocalWorkspaceIntent
    data class ChangeText(val path: String, val text: String) : LocalWorkspaceIntent
    data object SaveSelectedFile : LocalWorkspaceIntent
    data object UndoSelectedFile : LocalWorkspaceIntent
    data object RedoSelectedFile : LocalWorkspaceIntent

    /** Creates [name] — a path relative to the directory the navigator shows — as an empty file or a folder. */
    data class CreateEntry(val name: String, val directory: Boolean) : LocalWorkspaceIntent

    /** Saves and runs the selected Python file. */
    data object RunSelectedFile : LocalWorkspaceIntent
    data object DismissRun : LocalWorkspaceIntent
    data object DismissFailure : LocalWorkspaceIntent

    /** Offers to install the package the last run could not import. */
    data object AskToInstallPackage : LocalWorkspaceIntent

    /** The first consent. [distribution] is what the user typed for a missing module, and unused for the file. */
    data class ConfirmPackageRequest(val distribution: String) : LocalWorkspaceIntent

    /** The second consent, over the resolved set. */
    data object ConfirmPackageReview : LocalWorkspaceIntent

    /** Cancels whichever install step is showing. Declining the `requirements.txt` prompt runs the script anyway. */
    data object DismissPackages : LocalWorkspaceIntent
}

sealed interface LocalWorkspaceEffect {
    /** A file opened from the navigator; the screen closes its drawer. */
    data object FileOpened : LocalWorkspaceEffect
}

/**
 * The workspace [slug]. Edits are saved a few seconds after typing stops, or at once on request. The Git pane reads
 * its status whenever it is shown, so it always reflects the saved files.
 */
class RealLocalWorkspaceComponent(
    componentContext: ComponentContext,
    private val slug: String,
    private val store: LocalWorkspaceStore,
    private val files: WorkspaceFiles,
    gitSource: GitRepositorySource,
    private val python: PythonRunner?,
    private val onBackNav: () -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : LocalWorkspaceComponent, ComponentContext by componentContext {
    private val feature = FeatureStore<LocalWorkspaceState, LocalWorkspaceMsg, LocalWorkspaceEffect>(
        LocalWorkspaceState(slug, canRunPython = python != null),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private val scope get() = feature.scope
    private val current: LocalWorkspaceState get() = feature.state.value
    private val autosaveJobs = mutableMapOf<String, Job>()
    private var lastInstallId = 0L

    // Set before a run's first suspension, so a second tap cannot slip in while the files are read.
    private var preparing = false
    private val gitPane = RealGitPaneComponent(
        childContext("git"),
        gitSource,
        mainContext,
        // The repository is the workspace root, so its paths are workspace paths.
        openInEditor = { path ->
            selectPane(LocalPane.Files)
            scope.launch { openFile(path, announce = true) }
        },
    )

    override val state: StateFlow<LocalWorkspaceState> = feature.state
    override val effects: Flow<LocalWorkspaceEffect> = feature.effects
    override val git: GitPaneComponent = gitPane

    /**
     * System back inside the workspace: it closes the file drawer, steps out of Git's own screens and back to Files.
     * Without it the root stack took the gesture and left the workspace from wherever the user was.
     */
    private val innerBack = BackCallback(isEnabled = false) { onBack() }

    init {
        backHandler.register(innerBack)
        gitPane.notices
            .onEach { notice ->
                if (notice is PaneNotice.Failure) feature.dispatch(LocalWorkspaceMsg.Failed(notice.text))
            }
            .launchIn(scope)
        feature.state
            .onEach { innerBack.isEnabled = it.navigatorOpen || it.selectedPane == LocalPane.Git }
            .launchIn(scope)
        lifecycle.doOnCreate {
            scope.launch {
                runCatchingCancellable { restore() }
                    .onFailure { Logger.w(it) { "Workspace $slug could not be restored" } }
            }
            listDirectory("")
        }
    }

    override fun onIntent(intent: LocalWorkspaceIntent) {
        when (intent) {
            is LocalWorkspaceIntent.SelectPane -> selectPane(intent.pane)
            is LocalWorkspaceIntent.SetNavigatorOpen ->
                feature.dispatch(LocalWorkspaceMsg.NavigatorOpenChanged(intent.open))
            is LocalWorkspaceIntent.OpenDirectory -> listDirectory(intent.directory)
            is LocalWorkspaceIntent.OpenFile -> scope.launch { openFile(intent.path, announce = true) }
            is LocalWorkspaceIntent.CloseFile -> closeFile(intent.path)
            is LocalWorkspaceIntent.SelectTab -> {
                feature.dispatch(LocalWorkspaceMsg.TabSelected(intent.index))
                saveTabs()
            }

            is LocalWorkspaceIntent.ChangeText ->
                edit(intent.path, LocalWorkspaceMsg.TextChanged(intent.path, intent.text))

            LocalWorkspaceIntent.SaveSelectedFile -> current.selectedFile?.let { save(it, manual = true) }
            LocalWorkspaceIntent.UndoSelectedFile ->
                current.selectedFile?.let { edit(it, LocalWorkspaceMsg.Undone(it)) }

            LocalWorkspaceIntent.RedoSelectedFile ->
                current.selectedFile?.let { edit(it, LocalWorkspaceMsg.Redone(it)) }

            is LocalWorkspaceIntent.CreateEntry -> createEntry(intent.name.trim(), intent.directory)
            LocalWorkspaceIntent.RunSelectedFile -> current.selectedFile?.let(::run)
            LocalWorkspaceIntent.DismissRun -> feature.dispatch(LocalWorkspaceMsg.RunDismissed)
            LocalWorkspaceIntent.DismissFailure -> feature.dispatch(LocalWorkspaceMsg.FailureDismissed)
            LocalWorkspaceIntent.AskToInstallPackage -> askToInstallMissingModule()
            is LocalWorkspaceIntent.ConfirmPackageRequest -> resolvePackages(intent.distribution)
            LocalWorkspaceIntent.ConfirmPackageReview -> installPackages()
            LocalWorkspaceIntent.DismissPackages -> dismissPackages()
        }
    }

    override fun onBack() {
        if (current.navigatorOpen) {
            feature.dispatch(LocalWorkspaceMsg.NavigatorOpenChanged(false))
            return
        }
        if (current.selectedPane == LocalPane.Git && gitPane.onBack()) return
        if (current.selectedPane == LocalPane.Git) {
            selectPane(LocalPane.Files)
            return
        }
        onBackNav()
    }

    private suspend fun restore() {
        val workspace = store.get(slug) ?: return
        feature.dispatch(LocalWorkspaceMsg.WorkspaceLoaded(workspace))
        val tabs = workspace.openedTabs.ifEmpty { runCatching { files.initialFiles(slug) }.getOrDefault(emptyList()) }
        feature.dispatch(LocalWorkspaceMsg.TabsRestored(tabs, workspace.selectedTab))
        tabs.forEach { load(it) }
    }

    private fun selectPane(pane: LocalPane) {
        feature.dispatch(LocalWorkspaceMsg.PaneSelected(pane))
        if (pane == LocalPane.Git) gitPane.refresh(manual = true)
    }

    private fun listDirectory(directory: String) {
        feature.dispatch(LocalWorkspaceMsg.DirectoryRequested(directory))
        scope.launch {
            try {
                feature.dispatch(LocalWorkspaceMsg.DirectoryListed(directory, files.list(slug, directory)))
            } catch (fault: WorkspaceFault) {
                feature.dispatch(LocalWorkspaceMsg.DirectoryFailed(directory, fault))
            }
        }
    }

    private suspend fun openFile(path: String, announce: Boolean) {
        val text = try {
            files.isText(slug, path)
        } catch (fault: WorkspaceFault) {
            feature.dispatch(LocalWorkspaceMsg.Failed(fault.text))
            return
        }
        if (!text) return
        val alreadyOpen = path in current.openedFiles
        feature.dispatch(LocalWorkspaceMsg.FileOpened(path))
        if (!alreadyOpen) load(path)
        if (announce) {
            feature.dispatch(LocalWorkspaceMsg.NavigatorOpenChanged(false))
            feature.emit(LocalWorkspaceEffect.FileOpened)
        }
        saveTabs()
    }

    private fun closeFile(path: String) {
        autosaveJobs.remove(path)?.cancel()
        if (current.editors[path]?.isDirty == true) save(path, manual = false)
        feature.dispatch(LocalWorkspaceMsg.FileClosed(path))
        saveTabs()
    }

    private fun load(path: String) {
        scope.launch {
            try {
                feature.dispatch(LocalWorkspaceMsg.EditorLoaded(path, files.readText(slug, path)))
            } catch (fault: WorkspaceFault) {
                feature.dispatch(LocalWorkspaceMsg.EditorFailed(path, fault))
            }
        }
    }

    private fun edit(path: String, msg: LocalWorkspaceMsg) {
        val before = current.editors[path]?.text
        feature.dispatch(msg)
        if (current.editors[path]?.text == before) return
        autosaveJobs.remove(path)?.cancel()
        val startedAt = Clock.System.now().toEpochMilliseconds()
        feature.dispatch(
            LocalWorkspaceMsg.AutosaveScheduled(path, AutosaveCountdown(startedAt, AUTOSAVE_DELAY_MILLIS, startedAt)),
        )
        autosaveJobs[path] = scope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            save(path, manual = false)
        }
    }

    private fun save(path: String, manual: Boolean) {
        autosaveJobs.remove(path)?.cancel()
        val editor = current.editors[path] ?: return
        if (!editor.isDirty || editor.loading) return
        val text = editor.text
        feature.dispatch(LocalWorkspaceMsg.SaveStarted(path))
        scope.launch {
            try {
                files.writeText(slug, path, text)
                feature.dispatch(LocalWorkspaceMsg.Saved(path, text))
                if (current.selectedPane == LocalPane.Git) gitPane.refresh()
            } catch (fault: WorkspaceFault) {
                feature.dispatch(LocalWorkspaceMsg.SaveFailed(path, fault))
                if (manual) feature.dispatch(LocalWorkspaceMsg.Failed(fault.text))
            }
        }
    }

    private fun createEntry(name: String, directory: Boolean) {
        val parent = current.navigator.directory
        val path = if (parent.isEmpty()) name else "$parent/$name"
        scope.launch {
            try {
                files.createEntry(slug, path, directory)
                listDirectory(parent)
                if (!directory) openFile(path, announce = true)
            } catch (fault: WorkspaceFault) {
                feature.dispatch(LocalWorkspaceMsg.Failed(fault.text))
            }
        }
    }

    /**
     * Runs [path] against the files as saved, so unsaved edits are written first. A `requirements.txt` that differs
     * from what is installed, and that the user has not declined, is offered for install before the run.
     */
    private fun run(path: String) {
        if (python == null || !isPythonScript(path)) return
        if (!current.canRun || preparing) return
        preparing = true
        scope.launch {
            val ready = try {
                prepareRun(path)
            } finally {
                preparing = false
            }
            if (ready) startRun(path)
        }
    }

    /** Saves [path] and checks `requirements.txt`; `false` when the script must not run yet. */
    private suspend fun prepareRun(path: String): Boolean {
        if (!saveForRun(path)) return false
        val declared = readRequirements()
        feature.dispatch(LocalPackagesMsg.RequirementsChecked((declared as? RequirementsParse.Rejected)?.line))
        val requirements = (declared as? RequirementsParse.Valid)?.requirements.orEmpty()
        val unreviewed = requirements.isNotEmpty() && isUnreviewed(requirements)
        if (unreviewed) {
            feature.dispatch(
                LocalPackagesMsg.InstallAsked(++lastInstallId, PackageSource.RequirementsFile(requirements), path),
            )
        }
        return !unreviewed
    }

    private suspend fun saveForRun(path: String): Boolean {
        val editor = current.editors[path]
        if (editor?.isDirty != true) return true
        return try {
            files.writeText(slug, path, editor.text)
            feature.dispatch(LocalWorkspaceMsg.Saved(path, editor.text))
            true
        } catch (fault: WorkspaceFault) {
            feature.dispatch(LocalWorkspaceMsg.Failed(fault.text))
            false
        }
    }

    private suspend fun startRun(path: String) {
        val runner = python ?: return
        feature.dispatch(LocalWorkspaceMsg.RunStarted(path))
        // A runner that throws ends the run as a failed one, so the Run button comes back.
        val result = runCatchingCancellable {
            runner.run(files.root(slug).toString(), files.packages(slug).toString(), path)
        }.getOrElse { failure ->
            Logger.w(failure) { "Run of $path stopped before it finished" }
            PythonRun(output = failure.message.orEmpty(), outcome = PythonRunOutcome.FAILED)
        }
        feature.dispatch(LocalWorkspaceMsg.RunFinished(path, result))
    }

    /** `requirements.txt`; no file means no requirements, and one too large to read is rejected as a whole. */
    private suspend fun readRequirements(): RequirementsParse = try {
        parseRequirements(files.readText(slug, REQUIREMENTS_FILE, MAX_REQUIREMENTS_FILE_BYTES))
    } catch (_: WorkspaceFault.NotFound) {
        RequirementsParse.Valid(emptyList())
    } catch (_: WorkspaceFault) {
        RequirementsParse.Rejected(line = 0)
    }

    /** Whether [requirements] is neither what is installed nor what the user declined. */
    private suspend fun isUnreviewed(requirements: List<Requirement>): Boolean {
        val lines = requirements.map(Requirement::line)
        return lines != files.readPackagesRecord(slug)?.requirements && lines != current.declinedRequirements
    }

    private fun askToInstallMissingModule() {
        val run = current.run ?: return
        val module = run.result?.missingModule ?: return
        if (current.packages != null) return
        feature.dispatch(LocalPackagesMsg.InstallAsked(++lastInstallId, PackageSource.MissingModule(module), run.path))
    }

    /** After the first consent: the requirements, the packages added before and the new one, resolved into staging. */
    private fun resolvePackages(distribution: String) {
        val runner = python ?: return
        val asking = current.packages as? PackageInstall.Asking ?: return
        val named = when (asking.source) {
            is PackageSource.RequirementsFile -> null
            is PackageSource.MissingModule -> requirementOrNull(distribution) ?: return
        }
        feature.dispatch(LocalPackagesMsg.InstallConfirmed(asking.id))
        scope.launch {
            val existing = files.readPackagesRecord(slug)
            // A package named after a failed import goes on top of what the user already agreed to; only the
            // requirements prompt reads requirements.txt.
            val declared = (asking.source as? PackageSource.RequirementsFile)?.requirements?.map(Requirement::line)
                ?: existing?.requirements.orEmpty()
            val record = PackagesRecord(declared, (existing?.added.orEmpty() + listOfNotNull(named?.line)).distinct())
            // The record came off the disk: each line is checked again before it goes anywhere.
            val requested = (record.requirements + record.added).distinct().mapNotNull(::requirementOrNull)
            feature.dispatch(LocalPackagesMsg.Resolved(asking.id, resolveSafely(runner, requested), record))
        }
    }

    /** After the second consent: the record goes into staging, and the installer unpacks the set and swaps it in. */
    private fun installPackages() {
        val runner = python ?: return
        val reviewing = current.packages as? PackageInstall.Reviewing ?: return
        feature.dispatch(LocalPackagesMsg.InstallReviewed(reviewing.id))
        scope.launch {
            val staging = files.packagesStaging(slug)
            val failure = try {
                files.writePackagesRecord(staging, reviewing.record)
                installSafely(runner, staging.toString(), files.packages(slug).toString())
            } catch (fault: WorkspaceFault) {
                feature.dispatch(LocalPackagesMsg.InstallDismissed)
                feature.dispatch(LocalWorkspaceMsg.Failed(fault.text))
                return@launch
            }
            // Leaving the screen does not stop Python; a dismissed install's answer is dropped here and in reduce.
            val stillWanted = current.packages?.id == reviewing.id
            feature.dispatch(LocalPackagesMsg.InstallFinished(reviewing.id, failure))
            if (failure == null && stillWanted) reviewing.thenRun?.let { startRun(it) }
        }
    }

    // An exception from the runner is a failed install, never a screen stuck on "installing".
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveSafely(runner: PythonRunner, requested: List<Requirement>): PackageResolution = try {
        runner.resolve(requested, files.packagesStaging(slug).toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PackageResolution.Failed(PackageFailure.REJECTED, "")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun installSafely(runner: PythonRunner, staging: String, target: String) = try {
        runner.install(staging, target)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PackageResolution.Failed(PackageFailure.REJECTED, "")
    }

    private fun dismissPackages() {
        val dismissed = current.packages
        feature.dispatch(LocalPackagesMsg.InstallDismissed)
        val declinedFile = dismissed?.source is PackageSource.RequirementsFile
        if (declinedFile && (dismissed is PackageInstall.Asking || dismissed is PackageInstall.Failed)) {
            dismissed.thenRun?.let { path -> scope.launch { startRun(path) } }
        }
    }

    private fun saveTabs() {
        val snapshot = current
        scope.launch {
            runCatchingCancellable { store.updateTabs(slug, snapshot.openedFiles, snapshot.safeSelectedTab) }
                .onFailure { Logger.w(it) { "Open tabs of workspace $slug could not be saved" } }
        }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 3_000L
        const val REQUIREMENTS_FILE = "requirements.txt"
    }
}
