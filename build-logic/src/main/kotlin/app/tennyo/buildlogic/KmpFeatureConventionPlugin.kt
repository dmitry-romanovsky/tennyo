package app.tennyo.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * An app feature module (`:app:feature:<name>`): Compose on Android and iOS, its own string resources, and access to
 * the design system, the link layer and the wire contract — never to another feature, unless its script adds one.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply(KmpComposeConventionPlugin::class.java)
        val featurePackage = "app.tennyo.mobile.feature.${name.replace('-', '.')}"
        extensions.getByType<TennyoTargetsExtension>().apply {
            android(namespace = featurePackage) {
                androidResources { enable = true }
                withHostTestBuilder {}.configure {}
            }
            ios()
        }
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":app:design"))
                implementation(project(":app:link"))
                implementation(project(":shared:contract"))
                implementation(libs.library("decompose"))
                implementation(libs.library("decompose-extensions-compose"))
                implementation(libs.library("kermit"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.library("kotlinx-coroutines-test"))
                implementation(libs.library("multiplatform-settings-test"))
                implementation(libs.library("ktor-client-mock"))
            }
            sourceSets.getByName("androidHostTest").dependencies {
                implementation(libs.library("junit"))
            }
        }
        extensions.getByType<ComposeExtension>().extensions.getByType<ResourcesExtension>().apply {
            packageOfResClass = "$featurePackage.generated.resources"
        }
    }
}
