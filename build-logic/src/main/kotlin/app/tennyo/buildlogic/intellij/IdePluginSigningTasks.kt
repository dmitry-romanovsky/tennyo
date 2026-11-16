package app.tennyo.buildlogic.intellij

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Base64

abstract class WriteIdePluginSigningFilesTask : DefaultTask() {
    @get:OutputFile
    abstract val certificateChainFile: RegularFileProperty

    @get:OutputFile
    abstract val privateKeyFile: RegularFileProperty

    @TaskAction
    fun writeFiles() {
        writeDecodedSecret(
            certificateChainFile.get().asFile,
            requiredSigningEnvironment("JETBRAINS_SIGNING_CERTIFICATE_CHAIN_BASE64"),
        )
        writeDecodedSecret(
            privateKeyFile.get().asFile,
            requiredSigningEnvironment("JETBRAINS_SIGNING_PRIVATE_KEY_BASE64"),
        )
    }

    private fun writeDecodedSecret(file: File, base64Value: String) {
        file.parentFile.mkdirs()
        file.writeText(decodeBase64(base64Value))
    }

    private fun requiredSigningEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: throw GradleException("Missing IDE plugin signing environment variable: $name.")

    private fun decodeBase64(base64Value: String): String =
        String(Base64.getDecoder().decode(base64Value), Charsets.UTF_8)
}

abstract class ValidateIdePluginSigningTask : DefaultTask() {
    @TaskAction
    fun validate() {
        val certificateChainBase64 = requiredSigningEnvironment("JETBRAINS_SIGNING_CERTIFICATE_CHAIN_BASE64")
        val privateKeyBase64 = requiredSigningEnvironment("JETBRAINS_SIGNING_PRIVATE_KEY_BASE64")
        requiredSigningEnvironment("JETBRAINS_SIGNING_PRIVATE_KEY_PASSWORD")

        runCatching { decodeBase64(certificateChainBase64) }
            .getOrElse {
                throw GradleException("JETBRAINS_SIGNING_CERTIFICATE_CHAIN_BASE64 is not valid Base64.", it)
            }
        runCatching { decodeBase64(privateKeyBase64) }
            .getOrElse {
                throw GradleException("JETBRAINS_SIGNING_PRIVATE_KEY_BASE64 is not valid Base64.", it)
            }
    }

    private fun requiredSigningEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: throw GradleException("Missing IDE plugin signing environment variable: $name.")

    private fun decodeBase64(base64Value: String): String =
        String(Base64.getDecoder().decode(base64Value), Charsets.UTF_8)
}
