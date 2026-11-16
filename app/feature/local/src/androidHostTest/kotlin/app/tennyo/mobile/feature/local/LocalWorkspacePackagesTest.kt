package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitBranches
import app.tennyo.mobile.feature.git.GitCheckoutTarget
import app.tennyo.mobile.feature.git.GitCommitDetails
import app.tennyo.mobile.feature.git.GitCommitSelection
import app.tennyo.mobile.feature.git.GitDiff
import app.tennyo.mobile.feature.git.GitHistoryPage
import app.tennyo.mobile.feature.git.GitOperationResult
import app.tennyo.mobile.feature.git.GitPushPreview
import app.tennyo.mobile.feature.git.GitRepositorySource
import app.tennyo.mobile.feature.git.GitSourceCapabilities
import app.tennyo.mobile.feature.git.GitStatus
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The install flow of a local workspace against a [FakePythonRunner] and real files in a temporary directory. */
class LocalWorkspacePackagesTest {
    private val root = Files.createTempDirectory("tennyo-workspace-packages").toFile()
    private val files = WorkspaceFiles(
        FileSystem.SYSTEM,
        File(root, "workspaces").toOkioPath(),
        File(root, "python-packages").toOkioPath(),
        Dispatchers.Unconfined,
    )
    private val store = JsonLocalWorkspaceStore(
        fileSystem = FileSystem.SYSTEM,
        indexFile = File(root, "workspace-index.json").toOkioPath(),
        files = files,
        now = { 1L },
        ioContext = Dispatchers.Unconfined,
    )
    private val python = FakePythonRunner()
    private var stagedRecord: String? = null

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun cancellingTheFirstDialogResolvesAndInstallsNothing(): Unit = runBlocking {
        python.runResult = missingFrontmatter
        val component = workspace()
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.AskToInstallPackage)
        assertIs<PackageInstall.Asking>(component.state.value.packages)

        component.onIntent(LocalWorkspaceIntent.DismissPackages)

        assertTrue(python.resolved.isEmpty() && python.installs.isEmpty())
        assertEquals(listOf(MAIN), python.runs)
        assertNull(component.state.value.packages)
    }

    @Test
    fun cancellingTheSecondDialogResolvesButInstallsNothing(): Unit = runBlocking {
        python.runResult = missingFrontmatter
        val component = workspace()
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.AskToInstallPackage)
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest("python-frontmatter"))
        assertIs<PackageInstall.Reviewing>(component.state.value.packages)

        component.onIntent(LocalWorkspaceIntent.DismissPackages)

        assertEquals(listOf(listOf("python-frontmatter")), python.resolved.map { it.map(Requirement::line) })
        assertTrue(python.installs.isEmpty())
        assertEquals(listOf(MAIN), python.runs)
    }

    @Test
    fun aDistributionTheAllowlistRefusesIsNeverResolved(): Unit = runBlocking {
        python.runResult = missingFrontmatter
        val component = workspace()
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.AskToInstallPackage)

        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest("pkg @ https://evil.example/x.whl"))

        assertTrue(python.resolved.isEmpty())
        assertIs<PackageInstall.Asking>(component.state.value.packages)
    }

    @Test
    fun cancellingARequirementsPromptStillRunsTheScriptOnce(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertIs<PackageInstall.Asking>(component.state.value.packages)
        assertTrue(python.runs.isEmpty())

        component.onIntent(LocalWorkspaceIntent.DismissPackages)

        assertEquals(listOf(MAIN), python.runs)
        assertTrue(python.resolved.isEmpty() && python.installs.isEmpty())

        // The declined list is not asked again while the workspace stays open.
        component.onIntent(LocalWorkspaceIntent.DismissRun)
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertEquals(listOf(MAIN, MAIN), python.runs)
    }

    @Test
    fun requirementsThatMatchTheRecordOrHaveNoRequirementsDoNotPrompt(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "# only a comment\n")
        files.writePackagesRecord(files.packages(SLUG), PackagesRecord(listOf("Markdown==2.6.11"), emptyList()))
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertEquals(listOf(MAIN), python.runs)
        assertNull(component.state.value.packages)

        files.writeText(SLUG, "requirements.txt", "# pinned\nMarkdown==2.6.11\n")
        component.onIntent(LocalWorkspaceIntent.DismissRun)
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertEquals(listOf(MAIN, MAIN), python.runs)
        assertNull(component.state.value.packages)
    }

    @Test
    fun aChangedRequirementsFilePrompts(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==3.0\n")
        files.writePackagesRecord(files.packages(SLUG), PackagesRecord(listOf("Markdown==2.6.11"), emptyList()))
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)

        assertIs<PackageInstall.Asking>(component.state.value.packages)
        assertTrue(python.runs.isEmpty())
    }

    @Test
    fun aRejectedRequirementsFileIsNotOfferedAndTheScriptRuns(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "ok==1\n--index-url https://evil.example/\n")
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)

        assertEquals(2, component.state.value.requirementsRejectedLine)
        assertEquals(listOf(MAIN), python.runs)
        assertNull(component.state.value.packages)
    }

    @Test
    fun theInstalledRecordMatchesTheFileAndADeletedWorkspaceForgetsIt(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        python.onInstall = { staging, packages ->
            File(staging).copyRecursively(File(packages), overwrite = true)
            stagedRecord = File(staging, "tennyo-packages.json").readText()
        }
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest(""))
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageReview)

        val staged = Json.decodeFromString<PackagesRecord>(stagedRecord!!)
        assertEquals(listOf("Markdown==2.6.11"), staged.requirements)
        assertEquals(listOf(MAIN), python.runs, "the script runs once the packages are in")
        assertEquals(staged, files.readPackagesRecord(SLUG))

        component.onIntent(LocalWorkspaceIntent.DismissRun)
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertEquals(listOf(MAIN, MAIN), python.runs, "an installed set does not prompt again")

        store.remove(SLUG)
        store.reserve("Demo")
        files.writeText(SLUG, MAIN, "print(1)\n")
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        val recreated = workspace(reserve = false)
        recreated.onIntent(LocalWorkspaceIntent.RunSelectedFile)

        assertIs<PackageInstall.Asking>(recreated.state.value.packages)
    }

    @Test
    fun aFailedInstallIsShownAndTheScriptDoesNotRun(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        python.installFailure = PackageResolution.Failed(PackageFailure.REJECTED, "bad wheel")
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest(""))
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageReview)

        val failed = component.state.value.packages as PackageInstall.Failed
        assertEquals(PackageFailure.REJECTED to "bad wheel", failed.failure to failed.detail)
        assertTrue(python.runs.isEmpty())
    }

    @Test
    fun dismissingAFailedRequirementsInstallRunsTheScriptAndDoesNotAskAgain(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        python.resolution = PackageResolution.Failed(PackageFailure.NETWORK, "")
        val component = workspace()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest(""))
        assertIs<PackageInstall.Failed>(component.state.value.packages)
        component.onIntent(LocalWorkspaceIntent.DismissPackages)

        assertEquals(listOf(MAIN), python.runs)
        component.onIntent(LocalWorkspaceIntent.DismissRun)
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertEquals(listOf(MAIN, MAIN), python.runs)
        assertNull(component.state.value.packages)
    }

    @Test
    fun aMissingModuleInstallResolvesOnlyWhatWasAgreedToAndTheNamedPackage(): Unit = runBlocking {
        files.writeText(SLUG, "requirements.txt", "Markdown==2.6.11\n")
        python.runResult = missingFrontmatter
        python.onInstall = { staging, packages -> File(staging).copyRecursively(File(packages), overwrite = true) }
        val component = workspace()
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.DismissPackages)

        component.onIntent(LocalWorkspaceIntent.AskToInstallPackage)
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageRequest("python-frontmatter"))
        component.onIntent(LocalWorkspaceIntent.ConfirmPackageReview)

        assertEquals(listOf(listOf("python-frontmatter")), python.resolved.map { it.map(Requirement::line) })
        assertEquals(PackagesRecord(emptyList(), listOf("python-frontmatter")), files.readPackagesRecord(SLUG))
        component.onIntent(LocalWorkspaceIntent.DismissRun)
        val reopened = workspace(reserve = false)
        reopened.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        assertIs<PackageInstall.Asking>(reopened.state.value.packages)
    }

    @Test
    fun twoRunsInARowStartOnlyOne(): Unit = runBlocking {
        val component = workspace()
        python.runGate = CompletableDeferred()

        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)
        component.onIntent(LocalWorkspaceIntent.RunSelectedFile)

        assertEquals(listOf(MAIN), python.runs)
        python.runGate!!.complete(Unit)
    }

    /** A component over workspace [SLUG] with `main.py` open, the first run's missing-module answer scripted. */
    private suspend fun workspace(reserve: Boolean = true): RealLocalWorkspaceComponent {
        if (reserve) {
            store.reserve("Demo")
            files.writeText(SLUG, MAIN, "print(1)\n")
        }
        return RealLocalWorkspaceComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { create() }),
            slug = SLUG,
            store = store,
            files = files,
            gitSource = UnusedGitSource,
            python = python,
            onBackNav = {},
            mainContext = Dispatchers.Unconfined,
        )
    }

    private companion object {
        const val SLUG = "demo"
        const val MAIN = "main.py"
        val missingFrontmatter = PythonRun("ModuleNotFoundError", PythonRunOutcome.FAILED, missingModule = "frontmatter")
    }
}

/** The Git pane is not exercised here; any call to it is a bug in the test. */
private object UnusedGitSource : GitRepositorySource {
    override val capabilities = GitSourceCapabilities()

    override suspend fun status(repositoryId: String?): GitStatus = unused()
    override suspend fun diff(repositoryId: String, changeId: String): GitDiff = unused()
    override suspend fun commit(repositoryId: String, selection: GitCommitSelection, message: String):
            GitOperationResult = unused()

    override suspend fun rollback(repositoryId: String, selection: GitCommitSelection): GitOperationResult = unused()
    override suspend fun history(repositoryId: String, cursor: String?, limit: Int): GitHistoryPage = unused()
    override suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails = unused()
    override suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff = unused()
    override suspend fun branches(repositoryId: String): GitBranches = unused()
    override suspend fun checkout(repositoryId: String, target: GitCheckoutTarget): GitOperationResult = unused()
    override suspend fun pushPreview(repositoryId: String): GitPushPreview = unused()
    override suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult =
        unused()

    private fun unused(): Nothing = error("The Git source is not used by this test")
}
