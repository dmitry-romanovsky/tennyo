plugins {
    id("tennyo.android.application")
    alias(libs.plugins.chaquopy)
}

private fun localBuildProperty(
    name: String,
    environmentName: String,
): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf(String::isNotBlank)
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.substringBefore("=").trim() == name }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf(String::isNotBlank)

private fun releaseSigningFilePath(path: String): String =
    path.replace(Regex("""^([A-Za-z])\\:"""), "\$1:")

private val releaseStoreFile = localBuildProperty("storeFile", "ANDROID_RELEASE_STORE_FILE")?.let(::releaseSigningFilePath)
private val releaseStorePassword = localBuildProperty("storePassword", "ANDROID_RELEASE_STORE_PASSWORD")
private val releaseKeyAlias = localBuildProperty("keyAlias", "ANDROID_RELEASE_KEY_ALIAS")
private val releaseKeyPassword = localBuildProperty("keyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
private val hasReleaseSigningConfig = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

private val tennyoVersionCode: Int = (findProperty("tennyoVersionCode") as String).toInt()
private val tennyoVersionName: String = findProperty("tennyoVersionName") as String

android {
    namespace = "app.tennyo.mobile.android"

    defaultConfig {
        applicationId = "app.tennyo.mobile"
        versionCode = tennyoVersionCode
        versionName = tennyoVersionName

        // Chaquopy ships a CPython per ABI; phones are arm64, emulators x86_64.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "TENNYO_ALLOW_UNSAFE_TRAFFIC", "false")
            isMinifyEnabled = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("boolean", "TENNYO_ALLOW_UNSAFE_TRAFFIC", "true")
            applicationIdSuffix = ".debug"
            versionNameSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// Python that runs the phone's local workspaces; see ChaquopyPythonRunner.
chaquopy {
    defaultConfig {
        version = "3.13"
        // The runner files compile on first use; the pip install below still needs a build-machine Python 3.13.
        pyc {
            src = false
        }
        // CI puts python3.13 on PATH; elsewhere name it in local.properties or the environment.
        localBuildProperty("chaquopy.buildPython", "CHAQUOPY_BUILD_PYTHON")?.let { buildPython(it) }
        // tennyo_packages.py resolves a workspace's own packages on the device with it.
        pip {
            install("packaging==26.3")
        }
    }
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":app:feature:local"))
    implementation(libs.koin.android)
}
