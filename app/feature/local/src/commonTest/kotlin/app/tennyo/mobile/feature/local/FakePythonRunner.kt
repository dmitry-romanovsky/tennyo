package app.tennyo.mobile.feature.local

import kotlinx.coroutines.CompletableDeferred

/** A [PythonRunner] that records its calls and answers what a test scripted. */
internal class FakePythonRunner : PythonRunner {
    var runResult = PythonRun("", PythonRunOutcome.SUCCEEDED)
    var resolution: PackageResolution = PackageResolution.Resolved(
        listOf(ResolvedPackage("pyyaml", "6.0.3", PackageIndex.CHAQUOPY)),
    )
    var installFailure: PackageResolution.Failed? = null

    /** Called with the staging and packages directories when [install] runs, to stand in for the installer. */
    var onInstall: (stagingDir: String, packagesDir: String) -> Unit = { _, _ -> }

    /** When set, [run] waits for it, so a test can tap again while a run is under way. */
    var runGate: CompletableDeferred<Unit>? = null

    val runs = mutableListOf<String>()
    val resolved = mutableListOf<List<Requirement>>()
    val installs = mutableListOf<String>()

    override suspend fun run(workspaceRoot: String, packagesDir: String, entry: String): PythonRun {
        runs += entry
        runGate?.await()
        return runResult
    }

    override suspend fun resolve(requirements: List<Requirement>, stagingDir: String): PackageResolution {
        resolved += requirements
        return resolution
    }

    override suspend fun install(stagingDir: String, packagesDir: String): PackageResolution.Failed? {
        installs += stagingDir
        onInstall(stagingDir, packagesDir)
        return installFailure
    }
}
