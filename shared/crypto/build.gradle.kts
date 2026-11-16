import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("tennyo.kmp.library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

tennyoTargets {
    jvm()
    android(namespace = "app.tennyo.crypto") {
        withHostTestBuilder {}.configure {}
    }
    ios()
    nodeJs(commonJs = true)
}

kotlin {
    // The JVM host and the Android app share one JCA implementation.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jca") {
                withCompilations {
                    it.platformType == KotlinPlatformType.jvm || it.platformType == KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:contract"))
            implementation(libs.okio)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}
