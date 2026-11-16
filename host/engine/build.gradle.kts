plugins {
    id("tennyo.kmp.library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

tennyoTargets {
    jvm()
    nodeJs(commonJs = true)
}

kotlin {
    compilerOptions.optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:contract"))
            api(project(":shared:crypto"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }

        jsMain.dependencies {
            implementation(npm("selfsigned", "2.4.1"))
            implementation(npm("ws", "8.18.3"))
            implementation(npm("bonjour-service", "1.3.0"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The IntelliJ plugin runs this code on the IDE's own kotlinx-coroutines (1.10.x). Compiled against 1.11, calls with a
// default argument bind to bridges 1.10 lacks (`Job.cancel$default`), and throw NoSuchMethodError inside the IDE.
configurations.named("jvmCompileClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutinesIde.get()}")
}
