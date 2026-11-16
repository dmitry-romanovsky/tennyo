package app.tennyo.buildlogic.intellij

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

abstract class ObfuscateIdePluginTask @Inject constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @get:Classpath
    abstract val proguardClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val proguardLibraryClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val obfuscatedPluginZip: RegularFileProperty

    @get:OutputFile
    abstract val obfuscationMappingFile: RegularFileProperty

    @get:OutputDirectory
    abstract val workingDirectory: DirectoryProperty

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    /** Package path (`app/tennyo/host/`) that marks a jar inside the plugin as ours and therefore obfuscated. */
    @get:Input
    abstract val ownedPackagePath: Property<String>

    /** Classes the IDE instantiates by name from `plugin.xml`, kept whole. */
    @get:Input
    abstract val keptClasses: ListProperty<String>

    @TaskAction
    fun obfuscate() {
        val inputZip = pluginZip.get().asFile
        val workDir = workingDirectory.get().asFile
        fs.delete { delete(workDir) }
        workDir.mkdirs()

        val expanded = workDir.resolve("expanded")
        fs.copy {
            from(archives.zipTree(inputZip))
            into(expanded)
        }

        val expandedJars = expanded.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .toList()
        // The plugin jar and :host:engine are both ours; one ProGuard run renames them consistently.
        val ownedJars = expandedJars.filter { it.containsOwnedClasses() }
        if (ownedJars.isEmpty()) {
            throw GradleException("Expected at least one host-owned jar inside $inputZip.")
        }
        val outputDirectory = workDir.resolve("proguard-output").also { it.mkdirs() }
        val obfuscatedJars = ownedJars.map { ownedJar ->
            val proguardInputJar = workDir.resolve("proguard-input-${ownedJar.name}")
            copyJarWithoutKotlinModule(sourceJar = ownedJar, targetJar = proguardInputJar)
            ObfuscatedJar(owned = ownedJar, input = proguardInputJar, output = outputDirectory.resolve(ownedJar.name))
        }

        val mappingFile = obfuscationMappingFile.get().asFile.also { file ->
            file.parentFile.mkdirs()
        }
        val dependencyJars = expandedJars.filterNot { it in ownedJars }
        // ProGuard must see compile-only IntelliJ/Compose APIs so it preserves override method names.
        val libraryJars = proguardLibraryFiles(
            javaRuntimeLibraryJars(javaLauncher.get().metadata.installationPath.asFile) +
                    dependencyJars +
                    proguardLibraryClasspath.files,
        )
        val rulesFile = workDir.resolve("ide-plugin-proguard.pro")
        rulesFile.writeText(
            idePluginObfuscationRules(
                jars = obfuscatedJars,
                libraryJars = libraryJars,
                mappingFile = mappingFile,
            )
        )

        execOperations.javaexec {
            javaLauncher.set(this@ObfuscateIdePluginTask.javaLauncher)
            classpath = proguardClasspath
            mainClass.set("proguard.ProGuard")
            args("@${rulesFile.absolutePath}")
        }
        obfuscatedJars.forEach { jar ->
            fs.copy {
                from(jar.output)
                into(jar.owned.parentFile)
            }
        }

        val outputZip = obfuscatedPluginZip.get().asFile
        outputZip.parentFile.mkdirs()
        zipDirectory(expanded, outputZip)
    }

    private fun File.containsOwnedClasses(): Boolean =
        ZipFile(this).use { zip ->
            zip.entries().asSequence().any { entry ->
                entry.name.startsWith(ownedPackagePath.get()) &&
                        entry.name.endsWith(".class")
            }
        }

    private fun javaRuntimeLibraryJars(javaHome: File): List<File> =
        javaHome.resolve("jmods")
            .listFiles { file -> file.isFile && file.extension == "jmod" }
            ?.sortedBy(File::getName)
            ?: emptyList()

    private fun proguardLibraryFiles(files: Iterable<File>): List<File> =
        files
            .filter(File::exists)
            .distinctBy { it.canonicalFile }
            .sortedBy(File::getAbsolutePath)

    private class ObfuscatedJar(val owned: File, val input: File, val output: File)

    private fun idePluginObfuscationRules(
        jars: List<ObfuscatedJar>,
        libraryJars: List<File>,
        mappingFile: File,
    ): String =
        $$$"""
        $$${jars.joinToString(separator = "\n") { "-injars ${it.input.proguardPath()}\n-outjars ${it.output.proguardPath()}" }}
        -printmapping $$${mappingFile.proguardPath()}
        $$${libraryJars.joinToString(separator = "\n") { "-libraryjars ${it.proguardPath()}" }}
        -dontshrink
        -dontoptimize
        -dontwarn **
        -dontnote
        -ignorewarnings
        # ProGuard 7.9.1 crashes while rewriting Kotlin metadata for some annotated Kotlin declarations.
        -dontprocesskotlinmetadata
        -keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
        -keep class kotlin.Metadata { *; }
        -keep class kotlinx.serialization.** { *; }
        -keep @kotlinx.serialization.Serializable class $$${ownedPackagePath.get().replace('/', '.')}** { *; }
        -keep class **$$serializer { *; }
        -keepclassmembers class ** {
            *** Companion;
            kotlinx.serialization.KSerializer serializer(...);
        }
        $$${keptClasses.get().joinToString(separator = "\n") { "-keep class $it { *; }" }}
        -keep class androidx.compose.** { *; }
        -keep class org.jetbrains.jewel.** { *; }
        """.trimIndent() + "\n"

    private fun File.proguardPath(): String =
        "'${absolutePath.replace("\\", "\\\\").replace("'", "\\'")}'"

    private fun copyJarWithoutKotlinModule(sourceJar: File, targetJar: File) {
        ZipFile(sourceJar).use { source ->
            ZipOutputStream(FileOutputStream(targetJar)).use { target ->
                source.entries().asSequence()
                    .filterNot { it.name.endsWith(".kotlin_module") }
                    .forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            source.getInputStream(entry).use { it.copyTo(target) }
                        }
                        target.closeEntry()
                    }
            }
        }
    }

    private fun zipDirectory(directory: File, outputZip: File) {
        ZipOutputStream(FileOutputStream(outputZip)).use { output ->
            directory.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.relativeTo(directory).invariantSeparatorsPath
                    output.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
        }
    }
}
