plugins {
    id("tennyo.kmp.library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

tennyoTargets {
    jvm()
    android(namespace = "app.tennyo.contract")
    ios()
    nodeJs()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
