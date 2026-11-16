package app.tennyo.host.engine.rpc

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.session.HostSession

internal fun HostSession.fileTree(call: LinkCall.FileTree): LinkResult =
    LinkResult.FileTree(surfaces.files.fileTree(surfaces.projectInfo.name, call.path))

internal fun HostSession.readFile(call: LinkCall.ReadFile): LinkResult =
    LinkResult.File(surfaces.files.readFileContent(call.path) ?: throw missingFile())

internal fun HostSession.writeFile(call: LinkCall.WriteFile): LinkResult {
    if (call.request.text.utf8SizeExceeds(LinkLimits.MAX_FILE_TEXT_BYTES)) {
        throw CallFault(LinkFaultCode.FILE_TOO_LARGE, "The file content exceeds the mobile editing limit.")
    }
    return LinkResult.File(surfaces.files.writeFileContent(call.request.path, call.request.text) ?: throw missingFile())
}

private fun missingFile(): CallFault =
    CallFault(LinkFaultCode.TARGET_MISSING, "The requested file is no longer available.")
