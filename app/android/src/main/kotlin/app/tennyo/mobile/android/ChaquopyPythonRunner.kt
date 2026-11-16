package app.tennyo.mobile.android

import android.content.Context
import app.tennyo.mobile.feature.local.PackageFailure
import app.tennyo.mobile.feature.local.PackageIndex
import app.tennyo.mobile.feature.local.PackageResolution
import app.tennyo.mobile.feature.local.PythonRun
import app.tennyo.mobile.feature.local.PythonRunOutcome
import app.tennyo.mobile.feature.local.PythonRunner
import app.tennyo.mobile.feature.local.Requirement
import app.tennyo.mobile.feature.local.ResolvedPackage
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Python on the device through Chaquopy's bundled CPython. It lives in the application shell because the Chaquopy
 * Gradle plugin only applies to an application module; the work itself is `tennyo_runner.py` and `tennyo_packages.py`
 * beside it. Packages install on the device, into app-private storage, only after the user consents.
 */
class ChaquopyPythonRunner(private val context: Context) : PythonRunner {
    // One interpreter: runs and installs take turns, since each redirects the process's stdout while it works.
    private val lock = Mutex()

    override suspend fun run(workspaceRoot: String, packagesDir: String, entry: String): PythonRun =
        python("tennyo_runner") { module ->
            try {
                runScript(module, workspaceRoot, packagesDir, entry)
            } catch (error: PyException) {
                PythonRun(error.message.orEmpty(), PythonRunOutcome.FAILED)
            }
        }

    private fun runScript(module: PyObject, workspaceRoot: String, packagesDir: String, entry: String): PythonRun {
        val answer = module.callAttr("run", workspaceRoot, packagesDir, "$workspaceRoot/$entry", RUN_TIMEOUT_SECONDS)
            .asList()
        return PythonRun(
            output = answer[1].toString(),
            outcome = when (answer[0].toString()) {
                "succeeded" -> PythonRunOutcome.SUCCEEDED
                "timed_out" -> PythonRunOutcome.TIMED_OUT
                else -> PythonRunOutcome.FAILED
            },
            missingModule = answer[2].toString().ifEmpty { null },
        )
    }

    override suspend fun resolve(requirements: List<Requirement>, stagingDir: String): PackageResolution =
        python("tennyo_packages") { module ->
            try {
                val answer = module.callAttr("resolve", requirements.map(Requirement::line), stagingDir).asList()
                val detail = answer[1]?.toString().orEmpty()
                when (val outcome = answer[0].toString()) {
                    "resolved" ->
                        PackageResolution.Resolved(answer[2].asList().map { it.asList().toResolvedPackage() })

                    else -> PackageResolution.Failed(packageFailure(outcome), detail)
                }
            } catch (error: PyException) {
                PackageResolution.Failed(PackageFailure.REJECTED, "")
            }
        }

    override suspend fun install(stagingDir: String, packagesDir: String): PackageResolution.Failed? =
        python("tennyo_packages") { module ->
            try {
                val answer = module.callAttr("install", stagingDir, packagesDir).asList()
                val outcome = answer[0].toString()
                val detail = answer[1]?.toString().orEmpty()
                if (outcome == "installed") null else PackageResolution.Failed(packageFailure(outcome), detail)
            } catch (error: PyException) {
                PackageResolution.Failed(PackageFailure.REJECTED, "")
            }
        }

    private suspend fun <T> python(moduleName: String, block: (PyObject) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            block(Python.getInstance().getModule(moduleName))
        }
    }

    private fun List<PyObject>.toResolvedPackage() = ResolvedPackage(
        name = this[0].toString(),
        version = this[1].toString(),
        index = if (this[2].toString() == "chaquopy") PackageIndex.CHAQUOPY else PackageIndex.PYPI,
    )

    private fun packageFailure(outcome: String) = when (outcome) {
        "not_found" -> PackageFailure.NOT_FOUND
        "no_compatible_wheel" -> PackageFailure.NO_COMPATIBLE_WHEEL
        "network" -> PackageFailure.NETWORK
        "timed_out" -> PackageFailure.TIMED_OUT
        "invalid_requirement" -> PackageFailure.INVALID_REQUIREMENT
        else -> PackageFailure.REJECTED
    }

    private companion object {
        const val RUN_TIMEOUT_SECONDS = 120
    }
}
