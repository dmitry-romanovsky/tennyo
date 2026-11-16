package app.tennyo.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** The targets a Tennyo module may compile for, each configured the one way the whole build uses it. */
abstract class TennyoTargetsExtension(private val project: Project) {
    private val kotlin: KotlinMultiplatformExtension
        get() = project.extensions.getByType()

    fun jvm() {
        kotlin.jvm()
    }

    /** Node only: the IDE hosts run on Node, and nothing in the build targets a browser. */
    fun nodeJs(commonJs: Boolean = false) {
        kotlin.js {
            nodejs()
            if (commonJs) useCommonJs()
        }
    }

    /** The two Apple targets the app ships: devices and Apple-silicon simulators. */
    fun ios() {
        kotlin.iosArm64()
        kotlin.iosSimulatorArm64()
    }

    fun android(namespace: String, configure: KotlinMultiplatformAndroidLibraryTarget.() -> Unit = {}) {
        project.pluginManager.apply("com.android.kotlin.multiplatform.library")
        kotlin.extensions.getByType<KotlinMultiplatformAndroidLibraryTarget>().apply {
            this.namespace = namespace
            compileSdk = AndroidSdk.COMPILE
            minSdk = AndroidSdk.MIN
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(JDK_VERSION.toString()))
            }
            configure()
        }
    }
}
