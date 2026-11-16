package app.tennyo.host.intellij

import app.tennyo.contract.ProjectChoice
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.project.Project
import java.net.InetAddress

internal fun ideLabel(): String =
    runCatching {
        InetAddress.getLocalHost().hostName
            .substringBefore('.')
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
        ?: ApplicationNamesInfo.getInstance().fullProductName

internal fun Project.toRemoteProjectChoice(): ProjectChoice =
    ProjectChoice(
        projectId = locationHash,
        projectName = name,
        projectPath = shortenProjectHomePath(basePath.orEmpty()),
    )
