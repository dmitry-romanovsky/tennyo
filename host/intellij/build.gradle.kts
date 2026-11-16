import app.tennyo.buildlogic.intellij.AssertIdeDebugPluginTask
import app.tennyo.buildlogic.intellij.BuildAndInstallPluginTask
import app.tennyo.buildlogic.intellij.GenerateIdePluginBuildInfoTask
import app.tennyo.buildlogic.intellij.GenerateIdePluginBundleTask
import app.tennyo.buildlogic.intellij.IdePluginBuildVariant
import app.tennyo.buildlogic.intellij.ObfuscateIdePluginTask
import app.tennyo.buildlogic.intellij.ValidateIdePluginSigningTask
import app.tennyo.buildlogic.intellij.ValidateIdePluginVariantMetadataTask
import app.tennyo.buildlogic.intellij.WriteIdePluginSigningFilesTask
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

plugins {
    id("tennyo.intellij.plugin")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val pluginArchiveName = "tennyo-host"

// This plugin's jar and :host:engine's are both ours; one ProGuard run renames them consistently.
val hostPackagePath = "app/tennyo/host/"

/**
 * Classes the IDE reaches by reflection, which ProGuard must keep whole: the ones plugin.xml names, and the settings
 * bean whose property names are the XML the IDE persists.
 */
val reflectivelyLoadedClasses = listOf(
    "app.tennyo.host.intellij.LinkServerService",
    "app.tennyo.host.intellij.ShowPopupAction",
    "app.tennyo.host.intellij.HostStatusBarWidgetFactory",
    "app.tennyo.host.intellij.HostIcons",
    "app.tennyo.host.intellij.IntelliJTerminalPort",
    "app.tennyo.host.intellij.HostGlobalSettings\$State",
)

val requestedIdePluginVariants = gradle.startParameter.taskNames
    .filter { taskName ->
        taskName.startsWith(":host:intellij:") ||
                taskName.startsWith("host:intellij:") ||
                taskName.startsWith("buildIde") ||
                taskName.startsWith("buildAndInstall") ||
                taskName == "publishPlugin"
    }
    .mapNotNull { taskName ->
        when {
            taskName.contains("Debug", ignoreCase = true) -> IdePluginBuildVariant.DEBUG

            taskName.contains("Release", ignoreCase = true) ||
                    taskName.contains("Signed", ignoreCase = true) -> IdePluginBuildVariant.RELEASE

            else -> null
        }
    }
    .distinct()

check(requestedIdePluginVariants.size <= 1) {
    "Build debug and release IDE plugin variants in separate Gradle invocations."
}

val selectedIdePluginVariant = requestedIdePluginVariants.singleOrNull() ?: IdePluginBuildVariant.RELEASE
val releasePluginVerificationRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.endsWith("buildIdeReleasePlugin") ||
            taskName.endsWith("publishPlugin")
}

val generateIdePluginBuildInfo =
    tasks.register("generateIdePluginBuildInfo", GenerateIdePluginBuildInfoTask::class.java) {
        description = "Generates build-variant constants for IDE plugin runtime code."

        debugPluginBuild.set(selectedIdePluginVariant.isDebug)
        pluginDisplayName.set(selectedIdePluginVariant.pluginDisplayName)
        defaultLogLevel.set(selectedIdePluginVariant.defaultLogLevel)
        outputDirectory.set(layout.buildDirectory.dir("generated/idePluginBuildInfo/${selectedIdePluginVariant.name.lowercase()}/kotlin"))
    }

val generateIdePluginBundle = tasks.register("generateIdePluginBundle", GenerateIdePluginBundleTask::class.java) {
    description = "Generates build-variant message bundle overrides for the IDE plugin."

    pluginDisplayName.set(selectedIdePluginVariant.pluginDisplayName)
    bundleTemplate.set(layout.projectDirectory.file("resources/messages/HostBundle.properties"))
    outputDirectory.set(layout.buildDirectory.dir("generated/idePluginBundle/${selectedIdePluginVariant.name.lowercase()}"))
}

val validateIdePluginVariantMetadata = tasks.register(
    "validateIdePluginVariantMetadata",
    ValidateIdePluginVariantMetadataTask::class.java,
) {
    description = "Validates generated IDE plugin variant metadata."

    dependsOn(generateIdePluginBundle)
    debugPluginBuild.set(selectedIdePluginVariant.isDebug)
    pluginDisplayName.set(selectedIdePluginVariant.pluginDisplayName)
    generatedResourcesDirectory.set(generateIdePluginBundle.flatMap { it.outputDirectory })
}

sourceSets.named("main") {
    kotlin.srcDir(generateIdePluginBuildInfo)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(validateIdePluginVariantMetadata)
    inputs.property("idePluginBuildVariant", selectedIdePluginVariant.name)
    exclude("META-INF/plugin.xml", "messages/HostBundle.properties")
    from(generateIdePluginBundle.map { it.outputDirectory.dir("messages") }) {
        into("messages")
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
        content {
            includeGroupByRegex("org\\.jetbrains\\.compose.*")
        }
    }
}

val proguard = configurations.create("proguard") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":shared:contract"))
    // The IDE ships kotlinx-coroutines; bundling a second copy would split coroutine types across class loaders.
    implementation(project(":host:engine")) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation(libs.kermit)
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)
    implementation(libs.jmdns)
    testImplementation(libs.kotlintestjunit5)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.jupiter.engine)
    proguard(libs.proguard.base)

    compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
    compileOnly("org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}")
    compileOnly("org.jetbrains.compose.animation:animation:${libs.versions.composeMultiplatform.get()}")
    compileOnly("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
    compileOnly(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.ui")
    }
    compileOnly("org.jetbrains.compose.ui:ui-tooling-preview-desktop:${libs.versions.composeMultiplatform.get()}")

    intellijPlatform {
        intellijIdeaUltimate("2026.2") {
            useInstaller.set(false)
        }

        @Suppress("UnstableApiUsage")
        composeUI()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.JUnit5)
        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()
        zipSigner()
    }
}

val buildPluginTask = tasks.named<BuildPluginTask>("buildPlugin")
val signPluginTask = tasks.named<SignPluginTask>("signPlugin")
val verifyPluginSignatureTask = tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature")
val verifyPluginTask = tasks.named<VerifyPluginTask>("verifyPlugin")
val publishPluginTask = tasks.named<PublishPluginTask>("publishPlugin")

val pluginSigningPrivateKeyPassword = providers.environmentVariable("JETBRAINS_SIGNING_PRIVATE_KEY_PASSWORD")
val idePluginSigningDirectory = layout.buildDirectory.dir("tmp/idePluginSigning")

val writeIdePluginSigningFiles =
    tasks.register("writeIdePluginSigningFiles", WriteIdePluginSigningFilesTask::class.java) {
        description = "Writes IDE plugin signing secrets to temporary PEM files."

        doNotTrackState("IDE plugin signing secrets are read at execution time and must not be fingerprinted.")
        certificateChainFile.set(idePluginSigningDirectory.map { it.file("certificate-chain.pem") })
        privateKeyFile.set(idePluginSigningDirectory.map { it.file("private-key.pem") })
    }

val cleanupIdePluginSigningFiles = tasks.register("cleanupIdePluginSigningFiles", Delete::class.java) {
    description = "Deletes temporary IDE plugin signing secret files."

    delete(idePluginSigningDirectory)
}

intellijPlatform {
    pluginConfiguration {
        name.set(selectedIdePluginVariant.pluginDisplayName)
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            current()
        }
    }
    signing {
        certificateChainFile.set(writeIdePluginSigningFiles.flatMap { it.certificateChainFile })
        privateKeyFile.set(writeIdePluginSigningFiles.flatMap { it.privateKeyFile })
        password.set(pluginSigningPrivateKeyPassword)
    }
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
    buildSearchableOptions = false
    // The plugin zip unpacks to this directory name in the IDE's plugins folder.
    projectName = pluginArchiveName
}

buildPluginTask {
    archiveBaseName.set(pluginArchiveName)
    archiveClassifier.set(selectedIdePluginVariant.archiveClassifier)
}

val assertIdeDebugPlugin = tasks.register("assertIdeDebugPlugin", AssertIdeDebugPluginTask::class.java) {
    description = "Validates that the debug IDE plugin artifact is unsigned and visibly marked."

    dependsOn(buildPluginTask)
    pluginZip.set(buildPluginTask.flatMap { it.archiveFile })
}

val validateIdePluginSigning = tasks.register("validateIdePluginSigning", ValidateIdePluginSigningTask::class.java) {
    description = "Validates signing secrets required for release IDE plugin builds."

    doNotTrackState("IDE plugin signing secrets are read at execution time and must not be fingerprinted.")
}

writeIdePluginSigningFiles {
    dependsOn(validateIdePluginSigning)
    finalizedBy(cleanupIdePluginSigningFiles)
}

val obfuscateIdeDebugPlugin = tasks.register("obfuscateIdeDebugPlugin", ObfuscateIdePluginTask::class.java) {
    description = "Builds an unsigned debug IDE plugin ZIP with host classes obfuscated by ProGuard."

    dependsOn(assertIdeDebugPlugin)
    pluginZip.set(buildPluginTask.flatMap { it.archiveFile })
    proguardClasspath.from(proguard)
    proguardLibraryClasspath.from(sourceSets.named("main").map { it.compileClasspath })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    obfuscatedPluginZip.set(layout.buildDirectory.file("distributions/$pluginArchiveName-${project.version}-debug-minified.zip"))
    obfuscationMappingFile.set(layout.buildDirectory.file("outputs/mapping/idePluginDebug/mapping.txt"))
    workingDirectory.set(layout.buildDirectory.dir("idePluginDebugObfuscation"))
    ownedPackagePath.set(hostPackagePath)
    keptClasses.set(reflectivelyLoadedClasses)
}

val obfuscateIdeReleasePlugin = tasks.register("obfuscateIdeReleasePlugin", ObfuscateIdePluginTask::class.java) {
    description = "Builds a release IDE plugin ZIP with host classes obfuscated by ProGuard."

    dependsOn(validateIdePluginSigning, buildPluginTask)
    pluginZip.set(buildPluginTask.flatMap { it.archiveFile })
    proguardClasspath.from(proguard)
    proguardLibraryClasspath.from(sourceSets.named("main").map { it.compileClasspath })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    obfuscatedPluginZip.set(layout.buildDirectory.file("distributions/$pluginArchiveName-${project.version}-release-minified.zip"))
    obfuscationMappingFile.set(layout.buildDirectory.file("outputs/mapping/idePluginRelease/mapping.txt"))
    workingDirectory.set(layout.buildDirectory.dir("idePluginReleaseObfuscation"))
    ownedPackagePath.set(hostPackagePath)
    keptClasses.set(reflectivelyLoadedClasses)
}

signPluginTask {
    dependsOn(validateIdePluginSigning, obfuscateIdeReleasePlugin)
    finalizedBy(cleanupIdePluginSigningFiles)
    archiveFile.set(obfuscateIdeReleasePlugin.flatMap { it.obfuscatedPluginZip })
    signedArchiveFile.set(
        layout.buildDirectory.file("distributions/$pluginArchiveName-${project.version}-release-minified-signed.zip")
    )
    doFirst {
        if (selectedIdePluginVariant != IdePluginBuildVariant.RELEASE) {
            throw GradleException("Only the release IDE plugin variant may be signed.")
        }
    }
}

verifyPluginSignatureTask {
    dependsOn(signPluginTask)
    finalizedBy(cleanupIdePluginSigningFiles)
    inputArchiveFile.set(signPluginTask.flatMap { it.signedArchiveFile })
}

cleanupIdePluginSigningFiles {
    mustRunAfter(signPluginTask, verifyPluginSignatureTask)
}

verifyPluginTask {
    if (releasePluginVerificationRequested) {
        dependsOn(signPluginTask)
        archiveFile.set(signPluginTask.flatMap { it.signedArchiveFile })
    }
}

val buildIdeDebugPlugin = tasks.register("buildIdeDebugPlugin") {
    group = "intellij platform"
    description = "Builds the unsigned debug IDE plugin ZIP."

    dependsOn(assertIdeDebugPlugin)
}

val buildDebugIdePluginWithProGuard = tasks.register("buildDebugIdePluginWithProGuard") {
    group = "intellij platform"
    description = "Builds the unsigned debug IDE plugin ZIP with ProGuard obfuscation."

    dependsOn(obfuscateIdeDebugPlugin)
}

val buildIdeReleasePlugin = tasks.register("buildIdeReleasePlugin") {
    group = "intellij platform"
    description = "Builds, obfuscates, signs, signature-verifies, and plugin-verifies the release IDE plugin ZIP."

    dependsOn(verifyPluginSignatureTask, verifyPluginTask)
}

publishPluginTask {
    archiveFile.set(signPluginTask.flatMap { it.signedArchiveFile })
    dependsOn(buildIdeReleasePlugin)
}

val buildAndInstallIdeDebugPlugin =
    tasks.register("buildAndInstallIdeDebugPlugin", BuildAndInstallPluginTask::class.java) {
        group = "intellij platform"
        description =
            "Builds the unsigned debug IDE plugin and installs it into the local IntelliJ IDEA plugins directory."

        dependsOn(assertIdeDebugPlugin)
        pluginZip.set(buildPluginTask.flatMap { it.archiveFile })
        customPluginsPath.set(providers.gradleProperty("userPluginsPath"))
        pluginDirectoryPrefix.set(pluginArchiveName)

        resolvedPluginsDir.set(provider {
            val override = customPluginsPath.orNull
            if (override != null) return@provider override

            val home = System.getProperty("user.home")
            val osName = System.getProperty("os.name")
            val (baseDir, prefix) = when {
                osName.startsWith("Mac") ->
                    Pair("$home/Library/Application Support/JetBrains", "IntelliJIdea")

                osName.startsWith("Windows") ->
                    Pair("${System.getenv("APPDATA")}/JetBrains", "IntelliJIdea")

                else ->
                    Pair("$home/.local/share/JetBrains", "IntelliJIdea")
            }
            val base = Path.of(baseDir)
            val ideaDir = Files.list(base).use { paths ->
                paths.filter { Files.isDirectory(it) && it.name.startsWith(prefix) }
                    .toList()
                    .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            }
                ?: error(
                    "No $prefix* directory found under $base. " +
                            "Set 'userPluginsPath' in gradle.properties to override."
                )
            ideaDir.resolve("plugins").toString()
        })
    }
