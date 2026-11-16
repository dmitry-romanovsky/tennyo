package app.tennyo.mobile.feature.local

/**
 * Runs Python scripts on this device and installs the packages they import. A platform that has no way to run Python
 * binds none, and the workspace then offers no Run button.
 */
interface PythonRunner {
    /**
     * Runs [entry], a path relative to [workspaceRoot], as `__main__` where it lives, with its folder, the workspace
     * and the packages in [packagesDir] importable, and answers with everything it printed.
     */
    suspend fun run(workspaceRoot: String, packagesDir: String, entry: String): PythonRun

    /**
     * Looks up [requirements] and everything they need, and downloads the wheels into [stagingDir]. Nothing becomes
     * importable until [install].
     */
    suspend fun resolve(requirements: List<Requirement>, stagingDir: String): PackageResolution

    /** Unpacks the set [resolve] left in [stagingDir] into [packagesDir]; `null` when it is installed. */
    suspend fun install(stagingDir: String, packagesDir: String): PackageResolution.Failed?
}

/**
 * What one run printed, stdout and stderr interleaved, and how it ended. [missingModule] is the top-level module the
 * script failed to import, if that is why it stopped.
 */
data class PythonRun(
    val output: String,
    val outcome: PythonRunOutcome,
    val missingModule: String? = null,
)

enum class PythonRunOutcome {
    SUCCEEDED,

    /** The script raised, or exited with a non-zero status. */
    FAILED,

    /** The script ran past the time limit and was stopped. */
    TIMED_OUT,
}

/** Where a distribution comes from: Chaquopy's index holds the Android builds of native packages. */
enum class PackageIndex { CHAQUOPY, PYPI }

data class ResolvedPackage(val name: String, val version: String, val index: PackageIndex)

sealed interface PackageResolution {
    /** Every distribution that would be installed, the requested ones and what they need. */
    data class Resolved(val packages: List<ResolvedPackage>) : PackageResolution

    /** [detail] is the offending distribution or requirement line. */
    data class Failed(val failure: PackageFailure, val detail: String) : PackageResolution
}

enum class PackageFailure {
    NOT_FOUND,
    NO_COMPATIBLE_WHEEL,
    NETWORK,
    TIMED_OUT,
    REJECTED,
    INVALID_REQUIREMENT,
}

/** A run's state in the workspace: running, or its last result for [path]. */
data class LocalRunState(
    val path: String,
    val running: Boolean = true,
    val result: PythonRun? = null,
)

/** Whether [path] is a script the runner takes. */
internal fun isPythonScript(path: String): Boolean = path.endsWith(".py", ignoreCase = true)
