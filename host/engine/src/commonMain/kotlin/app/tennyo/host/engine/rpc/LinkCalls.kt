package app.tennyo.host.engine.rpc

import app.tennyo.contract.Capability
import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkResult
import app.tennyo.contract.SessionEndReason
import app.tennyo.host.engine.agents.ChatBackend
import app.tennyo.host.engine.session.HostSession

/**
 * Calls whose effect depends on the order they arrive in: what is on screen, what is typed into a terminal, and the
 * end of the session, whose reply must still go out after the session scope is gone. The channel runs them one at a
 * time in arrival order; every other call runs as soon as a worker is free.
 */
val LinkCall.runsInOrder: Boolean
    get() = when (this) {
        LinkCall.StopSession,
        is LinkCall.ShowPane,
        LinkCall.OpenTerminalWindow,
        is LinkCall.TerminalInput,
        is LinkCall.SelectTerminal,
            -> true

        else -> false
    }

/** Calls answered only once the agent the user configured answers, which may be minutes later or never. */
val LinkCall.waitsOnAgent: Boolean
    get() = when (this) {
        is LinkCall.ChatStart,
        is LinkCall.ChatListSessions,
        is LinkCall.ChatHistory,
        is LinkCall.ChatAuthenticate,
        is LinkCall.ChatSetMode,
        is LinkCall.ChatSetConfig,
            -> true

        else -> false
    }

/** Runs a call that does not [LinkCall.runsInOrder]. Throws [CallFault] when it cannot be carried out. */
@Suppress("CyclomaticComplexMethod")
suspend fun HostSession.handle(call: LinkCall): LinkResult =
    when (call) {
        is LinkCall.Stats -> sessionStats()
        is LinkCall.FileTree -> fileTree(call)
        is LinkCall.ReadFile -> readFile(call)
        is LinkCall.WriteFile -> writeFile(call)
        is LinkCall.EditorTabs -> LinkResult.EditorTabs(editor.editorTabsState())
        is LinkCall.EditorTabActionCall -> handleEditorTabAction(call)
        is LinkCall.LaunchConfigurations ->
            LinkResult.LaunchConfigurations(launch.launchConfigurations().orFaultUnavailable())
        is LinkCall.LaunchCandidatesForFile ->
            LinkResult.LaunchCandidates(launch.candidatesForFile(call.path))
        is LinkCall.SelectLaunchConfiguration ->
            LinkResult.LaunchConfigurations(launch.selectLaunchConfiguration(call.id).orFaultUnavailable())
        is LinkCall.StartLaunch -> LinkResult.Done(launch.startLaunchConfiguration(call.configurationId))
        is LinkCall.StopLaunch -> LinkResult.Done(launch.stopLaunchConfiguration(call.configurationId))
        is LinkCall.LaunchTabs -> LinkResult.LaunchTabs(launch.launchTabsState().orFaultUnavailable())
        is LinkCall.LaunchOutput -> launchOutput(call)
        is LinkCall.SelectLaunchTab -> LinkResult.Done(launch.selectLaunchTab(call.tabId))
        is LinkCall.CloseLaunchTab -> LinkResult.Done(launch.closeLaunchTab(call.tabId))
        is LinkCall.TerminalTabs -> LinkResult.TerminalTabs(terminal.terminalTabsState(includeSnapshots = false))
        is LinkCall.CreateTerminal -> createTerminal()
        is LinkCall.TerminalSnapshot -> terminalSnapshot(call)
        is LinkCall.CloseTerminal -> LinkResult.Done(terminal.closeTerminal(call.terminalId))
        is LinkCall.GitStatus -> gitStatus(call)
        is LinkCall.GitDiff -> gitDiff(call)
        is LinkCall.GitCommitFileDiff -> gitCommitFileDiff(call)
        is LinkCall.GitBranches -> gitBranches(call)
        is LinkCall.GitHistory -> gitHistory(call)
        is LinkCall.GitCommitDetail -> gitCommitDetail(call)
        is LinkCall.GitPushPreview -> gitPushPreview(call)
        is LinkCall.GitPush -> LinkResult.GitOperation(git.gitPush(call.request))
        is LinkCall.GitCheckout -> LinkResult.GitOperation(git.gitCheckout(call.request))
        is LinkCall.GitCommit -> LinkResult.GitOperation(git.gitCommit(call.request))
        is LinkCall.GitRollback -> LinkResult.GitOperation(git.gitRollback(call.request))
        is LinkCall.GitFetch -> LinkResult.GitOperation(git.gitFetch(call.repositoryId))
        is LinkCall.GitPull -> LinkResult.GitOperation(git.gitPull(call.repositoryId))
        is LinkCall.GitMerge -> LinkResult.GitOperation(git.gitMerge(call.repositoryId))
        is LinkCall.GitCreateBranch -> LinkResult.GitOperation(git.gitCreateBranch(call.repositoryId, call.name))
        is LinkCall.GitDeleteBranch ->
            LinkResult.GitOperation(git.gitDeleteBranch(call.repositoryId, call.branchId, call.expectedRevision))
        is LinkCall.GitProjectInfo -> gitProjectInfo(call)
        is LinkCall.GitSaveRemote ->
            LinkResult.GitOperation(git.gitSaveRemote(call.repositoryId, call.previousName, call.remote))
        is LinkCall.GitRemoveRemote -> LinkResult.GitOperation(git.gitRemoveRemote(call.repositoryId, call.name))
        is LinkCall.GitRunProjectAction ->
            LinkResult.GitOperation(git.gitProjectAction(call.repositoryId, call.action))
        LinkCall.ChatSnapshot -> LinkResult.Chat(chatOrFault().snapshot())
        is LinkCall.ChatStart -> LinkResult.Chat(chatOrFault().start(call.agentId, call.sessionId))
        is LinkCall.ChatListSessions -> LinkResult.Chat(chatOrFault().listSessions(call.agentId))
        LinkCall.ChatHistory -> LinkResult.ChatHistory(chatOrFault().history())
        is LinkCall.ChatAuthenticate -> LinkResult.Chat(chatOrFault().authenticate(call.methodId))
        is LinkCall.ChatPrompt -> LinkResult.Done(chatOrFault().prompt(call.text, call.attachments))
        is LinkCall.ChatSetMode -> LinkResult.Done(chatOrFault().setMode(call.modeId))
        is LinkCall.ChatSetConfig -> LinkResult.Done(chatOrFault().setConfig(call.configId, call.value))
        is LinkCall.ChatAnswerForm ->
            LinkResult.Done(chatOrFault().answerForm(call.requestId, call.action, call.values))
        LinkCall.ChatCancel -> LinkResult.Done(chatOrFault().cancel())
        is LinkCall.ChatAnswer -> LinkResult.Done(chatOrFault().answer(call.requestId, call.optionId))
        LinkCall.StopSession,
        is LinkCall.ShowPane,
        LinkCall.OpenTerminalWindow,
        is LinkCall.TerminalInput,
        is LinkCall.SelectTerminal,
            -> handleInOrder(call)
    }

/** Runs a call that [LinkCall.runsInOrder], on the channel's ordered queue. */
fun HostSession.handleInOrder(call: LinkCall): LinkResult =
    when (call) {
        LinkCall.StopSession -> stopFromClient()
        is LinkCall.ShowPane -> showPane(call)
        LinkCall.OpenTerminalWindow -> LinkResult.Done(terminal.ensureTerminalOpen())
        is LinkCall.TerminalInput -> terminalInput(call)
        is LinkCall.SelectTerminal -> selectTerminal(call)
        else -> throw CallFault(LinkFaultCode.INVALID_CALL, "This call does not launch in order.")
    }

private fun HostSession.handleEditorTabAction(call: LinkCall.EditorTabActionCall): LinkResult {
    val action = call.request.action
    val result = editor.performTabAction(action, call.request.path)
    return LinkResult.Done(result)
}

fun sessionExpirationMessage(reason: SessionEndReason): String =
    when (reason) {
        SessionEndReason.PendingExpired ->
            "Pairing approval timed out. Start a new Tennyo session on your computer."

        SessionEndReason.IdleExpired ->
            "Mobile device connection timed out. Start a new Tennyo session on your computer."

        SessionEndReason.AbsoluteExpired ->
            "The remote session reached its maximum lifetime. Start a new Tennyo session on your computer."
    }

private fun HostSession.chatOrFault(): ChatBackend =
    chat?.takeIf { hasCapability(Capability.CHAT) }
        ?: throw CallFault(LinkFaultCode.UNSUPPORTED, "This host runs no agents.")

/** Launch state is absent without a launch surface, which a session missing `LAUNCH_CONFIGURATIONS` never claims. */
private fun <T : Any> T?.orFaultUnavailable(): T =
    this ?: throw CallFault(LinkFaultCode.UNSUPPORTED, "Launch configurations are unavailable for this session.")
