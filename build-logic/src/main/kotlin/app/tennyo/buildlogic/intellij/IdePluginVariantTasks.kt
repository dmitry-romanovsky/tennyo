package app.tennyo.buildlogic.intellij

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** The two IDE plugin builds: debug keeps DEBUG logging and a visible `[debug]` name; release is what ships. */
enum class IdePluginBuildVariant(
    val displayNameSuffix: String,
    val archiveSuffix: String,
    val defaultLogLevel: String,
) {
    DEBUG(
        displayNameSuffix = " [debug]",
        archiveSuffix = "debug",
        defaultLogLevel = "DEBUG",
    ),
    RELEASE(
        displayNameSuffix = "",
        archiveSuffix = "",
        defaultLogLevel = "INFO",
    );

    val pluginDisplayName: String = "Tennyo Companion$displayNameSuffix"
    val archiveClassifier: String? = archiveSuffix.ifBlank { null }
    val isDebug: Boolean = name == "DEBUG"
}

abstract class GenerateIdePluginBuildInfoTask : DefaultTask() {
    @get:Input
    abstract val debugPluginBuild: Property<Boolean>

    @get:Input
    abstract val pluginDisplayName: Property<String>

    @get:Input
    abstract val defaultLogLevel: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputRoot = outputDirectory.get().asFile
        // Clear first: a leftover file from a previous package layout would still be compiled.
        outputRoot.deleteRecursively()
        val packageDir = outputRoot
            .resolve("app/tennyo/host/intellij")
            .also { it.mkdirs() }
        packageDir.resolve("HostBuildInfo.kt").writeText(
            """
            package app.tennyo.host.intellij

            object HostBuildInfo {
                const val isDebugPluginBuild: Boolean = ${debugPluginBuild.get()}
                const val pluginDisplayName: String = "${pluginDisplayName.get()}"
                val defaultLogLevel: HostLogLevel = HostLogLevel.${defaultLogLevel.get()}
            }
            """.trimIndent() + "\n",
        )
    }
}

abstract class GenerateIdePluginBundleTask : DefaultTask() {
    @get:InputFile
    abstract val bundleTemplate: RegularFileProperty

    @get:Input
    abstract val pluginDisplayName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        val messages = output.resolve("messages").also { it.mkdirs() }
        val displayName = pluginDisplayName.get()

        val bundleOverrides = mapOf(
            "action.app.tennyo.host.ShowPopup.text" to displayName,
            "tennyo.companion.session.dialog.title" to displayName,
            "notification.group.tennyo.companion" to "$displayName session notifications",
            "notification.connection.request.title" to "$displayName connection request",
            "popup.title" to displayName,
        )
        val generatedBundle = bundleTemplate.get().asFile.readLines().joinToString(separator = "\n") { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "")
            if (key in bundleOverrides) "$key=${bundleOverrides.getValue(key)}" else line
        }
        messages.resolve("HostBundle.properties").writeText(generatedBundle + "\n")
    }
}

abstract class ValidateIdePluginVariantMetadataTask : DefaultTask() {
    @get:Input
    abstract val debugPluginBuild: Property<Boolean>

    @get:Input
    abstract val pluginDisplayName: Property<String>

    @get:InputDirectory
    abstract val generatedResourcesDirectory: DirectoryProperty

    @TaskAction
    fun validate() {
        val resources = generatedResourcesDirectory.get().asFile
        val bundle = resources.resolve("messages/HostBundle.properties").readText()
        val displayName = pluginDisplayName.get()

        check("popup.title=$displayName" in bundle) {
            "Generated bundle does not contain the expected popup title '$displayName'."
        }
        check(("[debug]" in bundle) == debugPluginBuild.get()) {
            "Generated bundle debug marker does not match the selected IDE plugin variant."
        }
    }
}

abstract class AssertIdeDebugPluginTask : DefaultTask() {
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @TaskAction
    fun assertDebugArtifact() {
        val archive = pluginZip.get().asFile
        check("-debug" in archive.name) {
            "Debug IDE plugin archive must include the debug suffix: ${archive.name}"
        }
        check(!archive.name.contains("signed", ignoreCase = true)) {
            "Debug IDE plugin archive must be unsigned: ${archive.name}"
        }
        val pluginXml = archive.readNestedPluginJarEntry("META-INF/plugin.xml")
        check("<name>Tennyo Companion [debug]</name>" in pluginXml) {
            "Debug IDE plugin descriptor must expose the debug display name."
        }
        val bundle = archive.readNestedPluginJarEntry("messages/HostBundle.properties")
        check("action.app.tennyo.host.ShowPopup.text=Tennyo Companion [debug]" in bundle) {
            "Debug IDE plugin action label must expose the debug display name."
        }
        check("popup.title=Tennyo Companion [debug]" in bundle) {
            "Debug IDE plugin popup title must expose the debug display name."
        }
    }

    private fun File.readNestedPluginJarEntry(entryName: String): String {
        ZipFile(this).use { pluginZip ->
            val pluginJarEntries = pluginZip.entries().asSequence()
                .filter { it.name.matches(Regex("^[^/]+/lib/[^/]+\\.jar$")) }
                .toList()
            for (pluginJarEntry in pluginJarEntries) {
                val pluginJarBytes = pluginZip.getInputStream(pluginJarEntry).use { it.readBytes() }
                ZipInputStream(ByteArrayInputStream(pluginJarBytes)).use { pluginJar ->
                    var nestedEntry = pluginJar.nextEntry
                    while (nestedEntry != null) {
                        if (nestedEntry.name == entryName) {
                            return String(pluginJar.readBytes(), Charsets.UTF_8)
                        }
                        nestedEntry = pluginJar.nextEntry
                    }
                }
            }
        }
        throw GradleException("Debug IDE plugin archive does not contain $entryName.")
    }
}
