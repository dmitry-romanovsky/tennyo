plugins {
    id("tennyo.kmp.feature")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
