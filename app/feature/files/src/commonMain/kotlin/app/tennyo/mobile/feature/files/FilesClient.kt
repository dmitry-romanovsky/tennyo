package app.tennyo.mobile.feature.files

import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.EditorTabActionRequest
import app.tennyo.contract.EditorTabsState
import app.tennyo.contract.FileContent
import app.tennyo.contract.FileTreeResponse
import app.tennyo.contract.LaunchCandidate
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.contract.WriteFileRequest
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.request

/** The project tree, file contents and the IDE's editor tabs. */
internal class FilesClient(private val link: LinkCalls) {
    suspend fun getFileTree(path: String? = null): FileTreeResponse =
        link.request<LinkResult.FileTree>(LinkCall.FileTree(path)).tree

    suspend fun readFile(path: String): FileContent =
        link.request<LinkResult.File>(LinkCall.ReadFile(path)).content

    suspend fun writeFile(request: WriteFileRequest): FileContent =
        link.request<LinkResult.File>(LinkCall.WriteFile(request)).content

    suspend fun getEditorTabs(): EditorTabsState =
        link.request<LinkResult.EditorTabs>(LinkCall.EditorTabs).state

    suspend fun getLaunchCandidates(path: String): List<LaunchCandidate> =
        link.request<LinkResult.LaunchCandidates>(LinkCall.LaunchCandidatesForFile(path)).candidates

    suspend fun startLaunch(configurationId: String) {
        link.call(LinkCall.StartLaunch(configurationId))
    }

    suspend fun stopLaunch(configurationId: String) {
        link.call(LinkCall.StopLaunch(configurationId))
    }

    suspend fun openEditorTab(path: String) {
        link.call(LinkCall.EditorTabActionCall(EditorTabActionRequest(path, EditorTabAction.OPEN)))
    }

    suspend fun selectEditorTab(path: String) {
        link.call(LinkCall.EditorTabActionCall(EditorTabActionRequest(path, EditorTabAction.SELECT)))
    }

    suspend fun closeEditorTab(path: String) {
        link.call(LinkCall.EditorTabActionCall(EditorTabActionRequest(path, EditorTabAction.CLOSE)))
    }
}
