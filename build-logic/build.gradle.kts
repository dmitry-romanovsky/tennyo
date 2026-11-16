plugins {
    `kotlin-dsl`
}

// The plugins themselves reach the build through the root project's `apply false` block, so every module sees one
// copy of each on a shared class loader; the convention code only compiles against them.
dependencies {
    compileOnly(libs.plugins.jetbrainsKotlinMultiplatform.asDependency())
    compileOnly(libs.plugins.androidKotlinMultiplatformLibrary.asDependency())
    compileOnly(libs.plugins.androidApplication.asDependency())
    compileOnly(libs.plugins.jetbrainsCompose.asDependency())
    compileOnly(libs.plugins.jetbrainsKotlinCompose.asDependency())
    compileOnly(libs.plugins.kotlinJvm.asDependency())
    compileOnly(libs.plugins.intelliJPlatform.asDependency())
    compileOnly(libs.plugins.detekt.asDependency())
}

fun Provider<PluginDependency>.asDependency(): Provider<String> =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "tennyo.kmp.library"
            implementationClass = "app.tennyo.buildlogic.KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "tennyo.kmp.compose"
            implementationClass = "app.tennyo.buildlogic.KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = "tennyo.kmp.feature"
            implementationClass = "app.tennyo.buildlogic.KmpFeatureConventionPlugin"
        }
        register("androidApplication") {
            id = "tennyo.android.application"
            implementationClass = "app.tennyo.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("intellijPlugin") {
            id = "tennyo.intellij.plugin"
            implementationClass = "app.tennyo.buildlogic.IntelliJPluginConventionPlugin"
        }
    }
}
