import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import javax.inject.Inject

plugins {
    id("tennyo.kmp.library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

version = providers.gradleProperty("pluginVersion").get()

tennyoTargets {
    nodeJs(commonJs = true)
}

kotlin {
    js {
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":host:engine"))
            implementation(project(":shared:contract"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // Bundled into the VSIX: the engine's Node listener needs them at run time, and esbuild resolves
            // them from this module's own dependencies rather than the engine's.
            implementation(npm("ws", "8.18.3"))
            implementation(npm("selfsigned", "2.4.1"))
            implementation(npm("bonjour-service", "1.3.0"))
            // VS Code's agent host protocol: its client, WebSocket transport and state reducers.
            implementation(npm("@microsoft/agent-host-protocol", "0.9.0"))
            // The screen of Tennyo's terminals, which the phone's snapshots are read from.
            implementation(npm("@xterm/headless", "6.0.0"))
            implementation(libs.kermit)
            implementation(devNpm("esbuild", "0.28.2"))
            implementation(devNpm("@vscode/vsce", "3.9.2"))
            implementation(devNpm("ovsx", "1.2.0"))
        }
    }
}

/**
 * The two VSIX builds, mirroring the IDE plugin: debug is unminified with a source map back to the Kotlin sources and
 * a visible `[debug]` name; release is minified and ships no source map.
 */
enum class VsixVariant(
    val taskSuffix: String,
    val displayNameSuffix: String,
    val fileSuffix: String,
    val minified: Boolean,
    val libraryDistributionTask: String,
    val libraryDirectory: String,
) {
    DEBUG(
        taskSuffix = "Debug",
        displayNameSuffix = " [debug]",
        fileSuffix = "-debug",
        minified = false,
        libraryDistributionTask = "jsNodeDevelopmentLibraryDistribution",
        libraryDirectory = "developmentLibrary",
    ),
    RELEASE(
        taskSuffix = "Release",
        displayNameSuffix = "",
        fileSuffix = "-release",
        minified = true,
        libraryDistributionTask = "jsNodeProductionLibraryDistribution",
        libraryDirectory = "productionLibrary",
    ),
}

val nodeExecutable = extensions.getByType<NodeJsEnvSpec>().executable
val nodeModules = rootProject.layout.buildDirectory.dir("js/node_modules")
val extensionDirectory = layout.projectDirectory.dir("extension")
val activationSmokeTest = layout.projectDirectory.file("smoke/activate-smoke.cjs")
// Kotlin/JS names the library after the root project and this module's Gradle path.
val libraryFileName = "${rootProject.name}-${path.removePrefix(":").replace(':', '-')}.js"

/** The extension's marketplace name; the VSIX file carries it too. */
val vsixName = "tennyo-host"

// The development and production library syncs share one packages directory, so building both VSIX variants in one
// run must finish the development distribution before the production sync overwrites that directory.
tasks.named("jsProductionLibraryCompileSync") { mustRunAfter("jsNodeDevelopmentLibraryDistribution") }
tasks.named("jsNodeProductionLibraryDistribution") { mustRunAfter("jsDevelopmentLibraryCompileSync") }

VsixVariant.entries.forEach { variant ->
    val variantName = variant.name.lowercase()
    val bundleDirectory = layout.buildDirectory.dir("vscode-bundle/$variantName")
    val bundledExtension = bundleDirectory.map { it.file("extension.js") }
    val stagedExtension = layout.buildDirectory.dir("vscode-extension/$variantName")
    val vsixFile = layout.buildDirectory.file("vsix/$vsixName-$version${variant.fileSuffix}.vsix")

    /** Bundles the Kotlin/JS library and everything it links into the one CommonJS file VS Code loads. */
    val bundle = tasks.register<Exec>("bundle${variant.taskSuffix}VsCodeExtension") {
        group = "vscode"
        description = "Bundles the $variantName extension into one CommonJS file with esbuild."
        dependsOn(variant.libraryDistributionTask, "kotlinNodeJsSetup", ":kotlinNpmInstall")
        val libraryDirectory = layout.buildDirectory.dir("dist/js/${variant.libraryDirectory}")
        inputs.dir(libraryDirectory)
        inputs.dir(nodeModules)
        inputs.property("minified", variant.minified)
        outputs.dir(bundleDirectory)
        doFirst {
            bundleDirectory.get().asFile.deleteRecursively()
            executable = nodeExecutable.get()
            // The Kotlin/JS distribution sits outside the shared node_modules, so esbuild is told where the
            // engine's run-time packages (`ws`, `selfsigned`, `bonjour-service`) live.
            environment("NODE_PATH", nodeModules.get().asFile.absolutePath)
            args(
                buildList {
                    add(nodeModules.get().file("esbuild/bin/esbuild").asFile.absolutePath)
                    add(libraryDirectory.get().file(libraryFileName).asFile.absolutePath)
                    addAll(listOf("--bundle", "--platform=node", "--format=cjs", "--external:vscode"))

                    if (variant.minified) add("--minify") else add("--sourcemap")
                    add("--outfile=${bundledExtension.get().asFile.absolutePath}")
                }
            )
        }
    }

    /** Loads the bundle in Node against a stand-in `vscode` module, so a broken export fails the build, not the editor. */
    val verifyBundle = tasks.register<Exec>("verify${variant.taskSuffix}VsCodeExtension") {
        group = "vscode"
        description = "Activates the $variantName bundle against a stub vscode module and checks the core self-check."
        dependsOn(bundle)
        inputs.file(activationSmokeTest)
        inputs.dir(bundleDirectory)
        doFirst {
            executable = nodeExecutable.get()
            args(activationSmokeTest.asFile.absolutePath, bundledExtension.get().asFile.absolutePath)
        }
    }

    /** The files the VSIX ships, with the manifest version and name taken from the build. */
    val stage = tasks.register<Sync>("stage${variant.taskSuffix}VsCodeExtension") {
        group = "vscode"
        dependsOn(verifyBundle)
        val manifestValues = mapOf(
            "version" to version.toString(),
            "displayName" to "Tennyo Companion${variant.displayNameSuffix}",
        )
        inputs.properties(manifestValues)
        from(extensionDirectory) {
            include("package.json", "README.md", ".vscodeignore", "icon.png")
            filesMatching("package.json") { expand(manifestValues) }
        }
        from(bundleDirectory) { into("dist") }
        from(rootProject.file("LICENSE"))
        into(stagedExtension)
    }

    tasks.register<Exec>("package${variant.taskSuffix}Vsix") {
        group = "vscode"
        description = "Packages build/vsix/$vsixName-<version>${variant.fileSuffix}.vsix with vsce."
        dependsOn(stage)
        inputs.dir(stagedExtension)
        outputs.file(vsixFile)
        workingDir(stagedExtension)
        doFirst {
            vsixFile.get().asFile.parentFile.mkdirs()
            executable = nodeExecutable.get()
            args(
                nodeModules.get().file("@vscode/vsce/vsce").asFile.absolutePath,
                "package",
                "--no-dependencies",
                "--allow-missing-repository",
                "--out",
                vsixFile.get().asFile.absolutePath,
            )
        }
    }
}

val releaseVsix = tasks.named("packageReleaseVsix")
val releaseVsixFile = layout.buildDirectory.file("vsix/$vsixName-$version${VsixVariant.RELEASE.fileSuffix}.vsix")

/** Registers a publish of the release VSIX; each CLI reads its token from the environment, never from the build. */
fun registerVsixPublish(name: String, registry: String, cli: String, tokenVariable: String) =
    tasks.register<Exec>(name) {
        group = "vscode"
        description = "Publishes the release VSIX to $registry; needs $tokenVariable in the environment."
        dependsOn(releaseVsix)
        doFirst {
            check(!System.getenv(tokenVariable).isNullOrBlank()) { "$tokenVariable is not set." }
            executable = nodeExecutable.get()
            args(
                nodeModules.get().file(cli).asFile.absolutePath,
                "publish",
                "--packagePath",
                releaseVsixFile.get().asFile.absolutePath,
            )
        }
    }

registerVsixPublish("publishVsixToMarketplace", "the Visual Studio Marketplace", "@vscode/vsce/vsce", "VSCE_PAT")
registerVsixPublish("publishVsixToOpenVsx", "Open VSX (Cursor, Devin Desktop)", "ovsx/bin/ovsx", "OVSX_PAT")

/** Installs a VSIX into every supported editor whose command-line launcher is on PATH or in its default location. */
abstract class InstallVsixTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFile
    abstract val vsix: RegularFileProperty

    @get:Input
    abstract val launcherCandidates: MapProperty<String, List<String>>

    @TaskAction
    fun install() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val pathDirectories = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        val launchers = launcherCandidates.get().mapNotNull { (editor, candidates) ->
            candidates.firstNotNullOfOrNull { candidate ->
                if (File(candidate).isAbsolute) {
                    File(candidate).takeIf { it.isFile }
                } else {
                    val names = if (windows) listOf("$candidate.cmd", "$candidate.exe") else listOf(candidate)
                    pathDirectories.firstNotNullOfOrNull { directory ->
                        names.map { File(directory, it) }.firstOrNull { it.isFile }
                    }
                }
            }?.let { editor to it }
        }
        if (launchers.isEmpty()) {
            throw GradleException("No VS Code, Cursor, or Devin Desktop launcher found on PATH or in its default location.")
        }
        val vsixPath = vsix.get().asFile.absolutePath
        launchers.forEach { (editor, launcher) ->
            logger.lifecycle("Installing ${vsix.get().asFile.name} into $editor ($launcher)")
            execOperations.exec {
                val command = listOf(launcher.absolutePath, "--install-extension", vsixPath, "--force")
                commandLine(if (windows) listOf("cmd", "/c") + command else command)
            }
        }
    }
}

tasks.register<InstallVsixTask>("buildAndInstallDebugVsix") {
    group = "vscode"
    description = "Builds the debug VSIX and installs it into the local VS Code, Cursor, and Devin Desktop."
    dependsOn("packageDebugVsix")
    vsix.set(layout.buildDirectory.file("vsix/$vsixName-$version${VsixVariant.DEBUG.fileSuffix}.vsix"))
    val localPrograms = System.getenv("LOCALAPPDATA")?.let { "$it/Programs" }
    launcherCandidates.set(
        mapOf(
            "VS Code" to listOfNotNull(
                "code",
                localPrograms?.let { "$it/Microsoft VS Code/bin/code.cmd" },
                "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
            ),
            "Cursor" to listOfNotNull(
                "cursor",
                localPrograms?.let { "$it/cursor/resources/app/bin/cursor.cmd" },
                "/Applications/Cursor.app/Contents/Resources/app/bin/cursor",
            ),
            "Devin Desktop" to listOfNotNull(
                "devin-desktop",
                localPrograms?.let { "$it/Devin/bin/devin-desktop.cmd" },
            ),
        )
    )
}
