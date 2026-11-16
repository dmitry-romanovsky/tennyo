// Loads every external Gradle plugin once, on the root class loader, so the convention plugins in build-logic and all
// modules share one copy of each.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.jetbrainsKotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.jetbrainsKotlinCompose) apply false
    alias(libs.plugins.jetbrainsKotlinSerialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.intelliJPlatform) apply false
}
