package app.tennyo.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The installable Android shell: SDK levels and Java compatibility shared with the KMP library modules. AGP 9 compiles
 * the shell's Kotlin itself, so no Kotlin Android plugin is applied (applying one is an error).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = AndroidSdk.COMPILE
            defaultConfig {
                minSdk = AndroidSdk.MIN
                targetSdk = AndroidSdk.TARGET
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(JDK_VERSION)
                targetCompatibility = JavaVersion.toVersion(JDK_VERSION)
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }
        // gradle.properties turns off the automatic stdlib dependency for the IntelliJ plugin, so the shell names it.
        dependencies.add("implementation", libs.library("kotlin-stdlib"))
    }
}
