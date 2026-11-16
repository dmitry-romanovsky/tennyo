plugins {
    id("tennyo.kmp.library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

tennyoTargets {
    android(namespace = "app.tennyo.mobile.link") {
        withHostTestBuilder {}.configure {}
    }
    ios()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:contract"))
            implementation(project(":shared:crypto"))
            api(libs.kotlinx.coroutines.core)
            api(libs.essenty.lifecycle)
            api(libs.multiplatform.settings)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.kermit)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
