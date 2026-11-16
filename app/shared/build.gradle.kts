import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("tennyo.kmp.compose")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

tennyoTargets {
    // com.android.kotlin.multiplatform.library has no build types/BuildConfig — build-variant-
    // specific values (TENNYO_ALLOW_UNSAFE_TRAFFIC, APP_VERSION_NAME, DEBUG) are pushed in from
    // :app:android at startup instead; see AndroidBuildConfig.kt.
    android(namespace = "app.tennyo.mobile") {
        androidResources {
            enable = true
        }
        withHostTestBuilder {}.configure {}
    }
    ios()
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "TennyoKit"
            binaryOption("bundleId", "app.tennyo.mobile.TennyoKit")
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:contract"))
            implementation(project(":app:design"))
            implementation(project(":app:link"))
            implementation(project(":app:feature:chat"))
            implementation(project(":app:feature:connect"))
            implementation(project(":app:feature:files"))
            implementation(project(":app:feature:launch"))
            implementation(project(":app:feature:terminal"))
            implementation(project(":app:feature:git"))
            implementation(project(":app:feature:settings"))
            implementation(project(":app:feature:local"))
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.decompose)
            implementation(libs.decompose.extensions.compose)
            implementation(libs.kermit)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.android.material)
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "app.tennyo.mobile.generated.resources"
}
