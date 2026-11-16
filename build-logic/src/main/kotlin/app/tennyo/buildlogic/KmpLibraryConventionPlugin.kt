package app.tennyo.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Kotlin Multiplatform module: shared compiler flags, the JDK toolchain and detekt over every `*Main` source set.
 * Targets are chosen per module through the `tennyoTargets` extension.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(JDK_VERSION)
            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
        extensions.create<TennyoTargetsExtension>("tennyoTargets", this)
        configureDetekt {
            file("src").listFiles { dir -> dir.name.endsWith("Main") }.orEmpty().map { it.resolve("kotlin") }
        }
    }
}
