package app.tennyo.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** A Kotlin Multiplatform module with Compose UI: the library conventions plus the Compose compiler and runtime. */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpLibraryConventionPlugin::class.java)
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                listOf(
                    "jetbrains-compose-runtime",
                    "jetbrains-compose-foundation",
                    "jetbrains-compose-material3",
                    "jetbrains-compose-ui",
                    "jetbrains-compose-components-resources",
                    "jetbrains-compose-ui-tooling-preview",
                    "jetbrains-compose-components-ui-tooling-preview",
                ).forEach { implementation(libs.library(it)) }
            }
            sourceSets.matching { it.name == "androidMain" }.configureEach {
                dependencies { implementation(libs.library("jetbrains-compose-ui-tooling")) }
            }
        }
    }
}
