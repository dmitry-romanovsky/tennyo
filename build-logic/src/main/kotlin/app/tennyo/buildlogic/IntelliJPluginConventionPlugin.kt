package app.tennyo.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * The IntelliJ host plugin: Kotlin/JVM with the Compose compiler for the popup, the IntelliJ Platform Gradle plugin,
 * and the plugin's flat `src` / `resources` / `test` layout.
 */
class IntelliJPluginConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.intellij.platform")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JDK_VERSION)
            sourceSets.getByName("main").kotlin.srcDir("src")
            sourceSets.getByName("test").kotlin.srcDir("test")
        }
        extensions.getByType<SourceSetContainer>().getByName("main").resources.srcDir("resources")
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        configureDetekt { listOf(file("src")) }
    }
}
