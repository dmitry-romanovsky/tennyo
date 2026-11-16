package app.tennyo.buildlogic.intellij

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.name

abstract class BuildAndInstallPluginTask @Inject constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {

    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val customPluginsPath: Property<String>

    @get:Input
    abstract val resolvedPluginsDir: Property<String>

    /** Earlier installs of this plugin to replace, matched by their directory name in the IDE plugins folder. */
    @get:Input
    abstract val pluginDirectoryPrefix: Property<String>

    @TaskAction
    fun install() {
        val pluginsDir = Path.of(resolvedPluginsDir.get())
        pluginsDir.toFile().mkdirs()

        Files.list(pluginsDir).use { paths ->
            paths.toList()
                .filter { Files.isDirectory(it) && it.name.startsWith(pluginDirectoryPrefix.get()) }
                .forEach { fs.delete { delete(it) } }
        }

        fs.copy {
            from(archives.zipTree(pluginZip))
            into(pluginsDir)
        }

        logger.lifecycle("Plugin installed to: $pluginsDir")
        logger.lifecycle("Restart IntelliJ IDEA to activate changes.")
    }
}

