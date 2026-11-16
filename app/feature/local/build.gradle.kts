plugins {
    id("tennyo.kmp.feature")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app:feature:git"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
        }

        androidMain.dependencies {
            implementation(libs.jgit)
            implementation(libs.work.runtime.ktx)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.android)
        }
    }
}
