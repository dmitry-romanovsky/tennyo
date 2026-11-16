package app.tennyo.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import java.io.File

/** Every JVM-flavoured compilation in the build targets the same JDK the IDE plugin and Android shell run on. */
internal const val JDK_VERSION = 21

internal object AndroidSdk {
    const val COMPILE = 37
    const val MIN = 33
    const val TARGET = 36
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in the version catalog") }

/**
 * One rule set for every module; the baseline is named after the Gradle path (`:app:shared` →
 * `baseline-app-shared.xml`) because several modules share a short name.
 */
internal fun Project.configureDetekt(sources: () -> List<File>) {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(rootProject.file("config/detekt/baseline-${path.removePrefix(":").replace(':', '-')}.xml"))
        buildUponDefaultConfig.set(true)
        parallel.set(true)
        source.setFrom(provider { sources().filter(File::isDirectory) })
    }
}
