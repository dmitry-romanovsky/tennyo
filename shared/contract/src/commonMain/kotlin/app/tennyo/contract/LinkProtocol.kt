package app.tennyo.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/*
 * Link protocol (docs/link-protocol.md). Three public HTTPS endpoints set a session up; everything after pairing
 * runs over one authenticated WebSocket channel carrying [LinkFrame]s.
 */

/** The link's HTTP surface on every host listener. */
object LinkEndpoints {
    const val PREFIX = "/api/tennyo"

    /** `GET`: what the session behind a ticket is, before the device proves anything. */
    const val HELLO = "$PREFIX/link/hello"

    /** `POST` a signed [PairRequest]: claim a session, confirm the pairing words, or resume a remembered pair. */
    const val PAIR = "$PREFIX/link/pair"

    /** `POST` a [DiscoverRequest]: find a remembered IDE on the local network, then connect to it. */
    const val DISCOVER = "$PREFIX/link/discover"

    /** `GET` with a WebSocket upgrade: the authenticated channel, opened by [LinkFrame.Open]. */
    const val CHANNEL = "$PREFIX/link/channel"

    /** Query parameter of [HELLO] naming the session; the session token travels as `Authorization: Bearer`. */
    const val SESSION_PARAMETER = "session"

    const val PAIR_BODY_LIMIT_BYTES = 64 * 1024
    const val DISCOVER_BODY_LIMIT_BYTES = 64 * 1024

    /** The largest body a listener buffers for [path]; the `GET` endpoints take none. */
    fun requestBodyLimitBytes(path: String): Int =
        when (path) {
            PAIR -> PAIR_BODY_LIMIT_BYTES
            DISCOVER -> DISCOVER_BODY_LIMIT_BYTES
            else -> 0
        }
}

/** Bounds of the channel. A frame longer than [MAX_FRAME_CHARS] travels as ordered [LinkFrame.Part]s. */
object LinkLimits {
    const val MAX_FRAME_CHARS = 256 * 1024

    /** The largest frame reassembled from parts: a file write at the editing limit, with room for JSON escaping. */
    const val MAX_ASSEMBLED_FRAME_CHARS = 8 * 1024 * 1024

    const val MAX_FILE_TEXT_BYTES = 2 * 1024 * 1024
    const val MAX_TERMINAL_INPUT_BYTES = 16 * 1024

    /** How long a signed request or channel open stays acceptable, in either direction of clock skew. */
    const val PROOF_FRESHNESS_MILLIS = 5 * 60 * 1_000L
}

/** One device's signature over a request: [SecurityTranscripts.deviceProof] signed with the device key. */
@Serializable
data class DeviceProof(
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val nonce: String,
    val signedAtEpochMillis: Long,
    val signature: String,
    val securityVersion: Int = ProtocolVersions.SECURITY_VERSION,
)

/** The body of [LinkEndpoints.PAIR]: the request as JSON text, and the device's proof over exactly that text. */
@Serializable
data class SignedPairRequest(
    val sessionId: String,
    val request: String,
    val proof: DeviceProof,
)

@Serializable
sealed interface PairRequest {
    @Serializable
    @SerialName("claim")
    data class Claim(val claim: SessionClaimRequest) : PairRequest

    @Serializable
    @SerialName("confirm")
    data class Confirm(val confirmation: SessionConfirmPairingRequest) : PairRequest

    @Serializable
    @SerialName("resumePair")
    data class ResumePair(val resume: RememberedPairResumeRequest) : PairRequest
}

@Serializable
sealed interface DiscoverRequest {
    @Serializable
    @SerialName("discover")
    data class Discover(val discover: DiscoveryLookup) : DiscoverRequest

    /** [request] is a [DiscoveryConnectRequest] as JSON text; [proof] signs exactly that text. */
    @Serializable
    @SerialName("connect")
    data class Connect(val request: String, val proof: DeviceProof) : DiscoverRequest

    /**
     * Asks whether this host is in pairing mode. Answered from the window state in memory: it starts no session,
     * reads no pair store, and does not count against the discovery lockout.
     */
    @Serializable
    @SerialName("offer")
    data object Offer : DiscoverRequest
}

/** The frames of the channel. Every frame is one WebSocket text message holding one JSON object keyed by `op`. */
@Serializable
sealed interface LinkFrame {
    /** Client, first frame: which session, proof of the approved device, and the last push already applied. */
    @Serializable
    @SerialName("open")
    data class Open(
        val sessionId: String,
        val sessionToken: String,
        val proof: DeviceProof,
        val lastPushSeq: Long? = null,
    ) : LinkFrame {
        /** The request text [proof] covers: the resume point, so a recorded open cannot be replayed from elsewhere. */
        val proofRequestText: String get() = lastPushSeq?.toString().orEmpty()
    }

    /**
     * Host, reply to [Open]: the stream the calls are MAC'd under. [snapshotRequired] says the missed pushes are no
     * longer in the replay history and the client reloads what it shows.
     */
    @Serializable
    @SerialName("opened")
    data class Opened(
        val streamId: String,
        val commandKeySeed: String,
        val serverTimeEpochMillis: Long,
        val snapshotRequired: Boolean,
        val securityVersion: Int = ProtocolVersions.SECURITY_VERSION,
    ) : LinkFrame

    /** Client: one call. [seq] only grows on a stream; [mac] is [SecurityTranscripts.callMac] under the stream key. */
    @Serializable
    @SerialName("call")
    data class Call(val id: Long, val seq: Long, val mac: String, val call: LinkCall) : LinkFrame

    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val result: LinkResult) : LinkFrame

    /** Host: [id] failed, or with no [id] the channel itself did (and is closed after this frame). */
    @Serializable
    @SerialName("fault")
    data class Fault(val id: Long? = null, val fault: LinkFaultCode, val message: String? = null) : LinkFrame

    /** Host: something changed. [seq] orders pushes across reconnects; a resume continues after the last applied. */
    @Serializable
    @SerialName("push")
    data class Push(val seq: Long, val push: LinkPush) : LinkFrame

    /** Either side: one slice of a frame longer than [LinkLimits.MAX_FRAME_CHARS]; [last] closes it. */
    @Serializable
    @SerialName("part")
    data class Part(val partId: Long, val index: Int, val last: Boolean, val text: String) : LinkFrame
}

@Serializable
sealed interface LinkCall {
    @Serializable
    @SerialName("stopSession")
    data object StopSession : LinkCall

    @Serializable
    @SerialName("stats")
    data object Stats : LinkCall

    /** The panel on screen and its selection; the host pushes full updates for it only. */
    @Serializable
    @SerialName("showPane")
    data class ShowPane(
        val panel: String,
        val selectedFilePath: String? = null,
        val selectedLaunchTabId: String? = null,
        val selectedTerminalId: String? = null,
    ) : LinkCall

    @Serializable
    @SerialName("fileTree")
    data class FileTree(val path: String? = null) : LinkCall

    @Serializable
    @SerialName("readFile")
    data class ReadFile(val path: String) : LinkCall

    @Serializable
    @SerialName("writeFile")
    data class WriteFile(val request: WriteFileRequest) : LinkCall

    @Serializable
    @SerialName("editorTabs")
    data object EditorTabs : LinkCall

    @Serializable
    @SerialName("editorTabAction")
    data class EditorTabActionCall(val request: EditorTabActionRequest) : LinkCall

    @Serializable
    @SerialName("launchConfigurations")
    data object LaunchConfigurations : LinkCall

    @Serializable
    @SerialName("launchCandidatesForFile")
    data class LaunchCandidatesForFile(val path: String) : LinkCall

    @Serializable
    @SerialName("selectLaunchConfiguration")
    data class SelectLaunchConfiguration(val id: String?) : LinkCall

    @Serializable
    @SerialName("startLaunch")
    data class StartLaunch(val configurationId: String) : LinkCall

    @Serializable
    @SerialName("stopLaunch")
    data class StopLaunch(val configurationId: String) : LinkCall

    @Serializable
    @SerialName("launchTabs")
    data object LaunchTabs : LinkCall

    @Serializable
    @SerialName("launchOutput")
    data class LaunchOutput(val tabId: String) : LinkCall

    @Serializable
    @SerialName("selectLaunchTab")
    data class SelectLaunchTab(val tabId: String) : LinkCall

    @Serializable
    @SerialName("closeLaunchTab")
    data class CloseLaunchTab(val tabId: String) : LinkCall

    @Serializable
    @SerialName("terminalTabs")
    data object TerminalTabs : LinkCall

    /** Shows the IDE's Terminal tool window. */
    @Serializable
    @SerialName("openTerminalWindow")
    data object OpenTerminalWindow : LinkCall

    @Serializable
    @SerialName("createTerminal")
    data object CreateTerminal : LinkCall

    @Serializable
    @SerialName("terminalSnapshot")
    data class TerminalSnapshot(val terminalId: String, val allStyles: Boolean = false) : LinkCall

    @Serializable
    @SerialName("selectTerminal")
    data class SelectTerminal(val terminalId: String) : LinkCall

    @Serializable
    @SerialName("terminalInput")
    data class TerminalInput(val terminalId: String, val text: String) : LinkCall

    @Serializable
    @SerialName("closeTerminal")
    data class CloseTerminal(val terminalId: String) : LinkCall

    @Serializable
    @SerialName("gitStatus")
    data class GitStatus(val repositoryId: String? = null) : LinkCall

    @Serializable
    @SerialName("gitDiff")
    data class GitDiff(val repositoryId: String, val changeId: String) : LinkCall

    @Serializable
    @SerialName("gitBranches")
    data class GitBranches(val repositoryId: String) : LinkCall

    @Serializable
    @SerialName("gitHistory")
    data class GitHistory(val repositoryId: String, val cursor: String? = null, val limit: Int) : LinkCall

    @Serializable
    @SerialName("gitCommitDetail")
    data class GitCommitDetail(val repositoryId: String, val commitId: String) : LinkCall

    @Serializable
    @SerialName("gitCommitFileDiff")
    data class GitCommitFileDiff(val repositoryId: String, val commitId: String, val fileId: String) : LinkCall

    @Serializable
    @SerialName("gitPushPreview")
    data class GitPushPreview(val repositoryId: String) : LinkCall

    @Serializable
    @SerialName("gitPush")
    data class GitPush(val request: GitPushRequest) : LinkCall

    @Serializable
    @SerialName("gitCheckout")
    data class GitCheckout(val request: GitCheckoutRequest) : LinkCall

    @Serializable
    @SerialName("gitCommit")
    data class GitCommit(val request: GitCommitRequest) : LinkCall

    @Serializable
    @SerialName("gitRollback")
    data class GitRollback(val request: GitRollbackRequest) : LinkCall

    @Serializable
    @SerialName("gitFetch")
    data class GitFetch(val repositoryId: String) : LinkCall

    /** Fast-forwards to the upstream branch; a diverged branch answers [GitOperationOutcome.NON_FAST_FORWARD]. */
    @Serializable
    @SerialName("gitPull")
    data class GitPull(val repositoryId: String) : LinkCall

    /** Merges the upstream branch after a pull could not fast-forward. */
    @Serializable
    @SerialName("gitMerge")
    data class GitMerge(val repositoryId: String) : LinkCall

    @Serializable
    @SerialName("gitCreateBranch")
    data class GitCreateBranch(val repositoryId: String, val name: String) : LinkCall

    /** Deletes the local branch [branchId] as of the branch list [expectedRevision]; its remote branch stays. */
    @Serializable
    @SerialName("gitDeleteBranch")
    data class GitDeleteBranch(val repositoryId: String, val branchId: String, val expectedRevision: Long) : LinkCall

    @Serializable
    @SerialName("gitProjectInfo")
    data class GitProjectInfo(val repositoryId: String) : LinkCall

    /** Adds [remote], or renames and repoints [previousName]. */
    @Serializable
    @SerialName("gitSaveRemote")
    data class GitSaveRemote(val repositoryId: String, val previousName: String?, val remote: GitRemote) : LinkCall

    @Serializable
    @SerialName("gitRemoveRemote")
    data class GitRemoveRemote(val repositoryId: String, val name: String) : LinkCall

    @Serializable
    @SerialName("gitProjectAction")
    data class GitRunProjectAction(val repositoryId: String, val action: GitProjectAction) : LinkCall

    @Serializable
    @SerialName("chatSnapshot")
    data object ChatSnapshot : LinkCall

    /**
     * Starts the conversation on [agentId], ending one already running: a new session, or the earlier [sessionId] the
     * agent listed, replayed into the transcript.
     */
    @Serializable
    @SerialName("chatStart")
    data class ChatStart(val agentId: String, val sessionId: String? = null) : LinkCall

    /** Lists [agentId]'s earlier sessions into [ChatState.sessions], starting the agent if it is not running. */
    @Serializable
    @SerialName("chatListSessions")
    data class ChatListSessions(val agentId: String) : LinkCall

    /**
     * Earlier conversations across agents, newest first, as far as the host can list them without starting an agent:
     * the editor's own agent sessions, and those of the ACP agent already running.
     */
    @Serializable
    @SerialName("chatHistory")
    data object ChatHistory : LinkCall

    @Serializable
    @SerialName("chatPrompt")
    data class ChatPrompt(val text: String, val attachments: List<ChatAttachment> = emptyList()) : LinkCall

    @Serializable
    @SerialName("chatSetMode")
    data class ChatSetMode(val modeId: String) : LinkCall

    @Serializable
    @SerialName("chatSetConfig")
    data class ChatSetConfig(val configId: String, val value: String) : LinkCall

    /** Signs in with [methodId] — on the computer — and then opens the session that was waiting for it. */
    @Serializable
    @SerialName("chatAuthenticate")
    data class ChatAuthenticate(val methodId: String) : LinkCall

    /** Answers a [ChatEntry.Form]; [values] are by field name, as the phone typed them. */
    @Serializable
    @SerialName("chatAnswerForm")
    data class ChatAnswerForm(
        val requestId: String,
        val action: ChatFormAction,
        val values: Map<String, String> = emptyMap(),
    ) : LinkCall

    @Serializable
    @SerialName("chatCancel")
    data object ChatCancel : LinkCall

    @Serializable
    @SerialName("chatAnswer")
    data class ChatAnswer(val requestId: String, val optionId: String) : LinkCall
}

@Serializable
sealed interface LinkResult {
    /** The call was carried out, or — [accepted] `false` — the IDE declined it. */
    @Serializable
    @SerialName("done")
    data class Done(val accepted: Boolean = true) : LinkResult

    @Serializable
    @SerialName("stats")
    data class Stats(val stats: SessionStats, val serverTimeEpochMillis: Long) : LinkResult

    @Serializable
    @SerialName("fileTree")
    data class FileTree(val tree: FileTreeResponse) : LinkResult

    @Serializable
    @SerialName("file")
    data class File(val content: FileContent) : LinkResult

    @Serializable
    @SerialName("editorTabs")
    data class EditorTabs(val state: EditorTabsState) : LinkResult

    @Serializable
    @SerialName("launchConfigurations")
    data class LaunchConfigurations(val state: LaunchConfigurationsState) : LinkResult

    @Serializable
    @SerialName("launchCandidates")
    data class LaunchCandidates(val candidates: List<LaunchCandidate>) : LinkResult

    @Serializable
    @SerialName("launchTabs")
    data class LaunchTabs(val state: LaunchTabsState) : LinkResult

    @Serializable
    @SerialName("launchOutput")
    data class LaunchOutput(val snapshot: LaunchOutputSnapshot) : LinkResult

    @Serializable
    @SerialName("terminalTabs")
    data class TerminalTabs(val state: TerminalTabsState) : LinkResult

    @Serializable
    @SerialName("terminal")
    data class Terminal(val tab: TerminalTab) : LinkResult

    @Serializable
    @SerialName("terminalSnapshot")
    data class TerminalSnapshot(val snapshot: TerminalSnapshotResponse) : LinkResult

    @Serializable
    @SerialName("gitStatus")
    data class GitStatus(val status: GitStatusResponse) : LinkResult

    @Serializable
    @SerialName("gitDiff")
    data class GitDiff(val diff: GitDiffResponse) : LinkResult

    @Serializable
    @SerialName("gitBranches")
    data class GitBranches(val branches: GitBranchesResponse) : LinkResult

    @Serializable
    @SerialName("gitHistory")
    data class GitHistory(val history: GitHistoryResponse) : LinkResult

    @Serializable
    @SerialName("gitCommitDetail")
    data class GitCommitDetail(val detail: GitCommitDetailResponse) : LinkResult

    @Serializable
    @SerialName("gitPushPreview")
    data class GitPushPreview(val preview: GitPushPreviewResponse) : LinkResult

    @Serializable
    @SerialName("gitOperation")
    data class GitOperation(val result: GitOperationResult) : LinkResult

    @Serializable
    @SerialName("gitProjectInfo")
    data class GitProjectInfo(val info: GitProjectInfoResponse) : LinkResult

    @Serializable
    @SerialName("chat")
    data class Chat(val state: ChatState) : LinkResult

    @Serializable
    @SerialName("chatHistory")
    data class ChatHistory(val entries: List<ChatHistoryEntry>) : LinkResult
}

/** Host-initiated updates, in [LinkFrame.Push.seq] order. */
@Serializable
sealed interface LinkPush {
    @Serializable
    @SerialName("stats")
    data class Stats(val stats: SessionStats) : LinkPush

    @Serializable
    @SerialName("capabilities")
    data class Capabilities(val capabilities: Set<Capability>) : LinkPush

    @Serializable
    @SerialName("editorTabs")
    data class EditorTabs(val state: EditorTabsState) : LinkPush

    @Serializable
    @SerialName("fileContent")
    data class FileContentChanged(val content: FileContent) : LinkPush

    @Serializable
    @SerialName("launchConfigurations")
    data class LaunchConfigurations(val state: LaunchConfigurationsState) : LinkPush

    @Serializable
    @SerialName("launchTabs")
    data class LaunchTabs(val state: LaunchTabsState) : LinkPush

    @Serializable
    @SerialName("launchOutput")
    data class LaunchOutputChanged(val output: LaunchOutput) : LinkPush

    @Serializable
    @SerialName("terminalTabs")
    data class TerminalTabs(val state: TerminalTabsState) : LinkPush

    @Serializable
    @SerialName("terminalFrame")
    data class TerminalOutput(val frame: TerminalFrame) : LinkPush

    @Serializable
    @SerialName("gitInvalidated")
    data class GitInvalidated(val revision: Long) : LinkPush

    @Serializable
    @SerialName("chat")
    data class Chat(val state: ChatState) : LinkPush

    @Serializable
    @SerialName("sessionEnded")
    data class SessionEnded(val reason: String, val reasonCode: SessionEndReason? = null) : LinkPush
}

@Serializable
enum class LinkFaultCode {
    /** The frame or call could not be read, or its arguments are invalid. */
    @SerialName("invalid_call")
    INVALID_CALL,

    /** The channel was not opened, or opened with a proof that does not verify. */
    @SerialName("unauthorized")
    UNAUTHORIZED,

    /** The device is not the one approved for this session. */
    @SerialName("wrong_device")
    WRONG_DEVICE,

    /** A call's sequence number did not move forward, or a proof was presented twice. */
    @SerialName("replayed")
    REPLAYED,

    @SerialName("mac_invalid")
    MAC_INVALID,

    @SerialName("session_expired")
    SESSION_EXPIRED,

    @SerialName("session_not_found")
    SESSION_NOT_FOUND,

    @SerialName("protocol_version_unsupported")
    PROTOCOL_VERSION_UNSUPPORTED,

    @SerialName("frame_too_large")
    FRAME_TOO_LARGE,

    @SerialName("target_missing")
    TARGET_MISSING,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("busy")
    BUSY,

    @SerialName("file_too_large")
    FILE_TOO_LARGE,

    @SerialName("git_target_stale")
    GIT_TARGET_STALE,

    @SerialName("git_cursor_invalid")
    GIT_CURSOR_INVALID,

    @SerialName("git_missing_upstream")
    GIT_MISSING_UPSTREAM,

    @SerialName("git_platform_failure")
    GIT_PLATFORM_FAILURE,

    @SerialName("operation_failed")
    OPERATION_FAILED,
}

/** The JSON form of every link message: sealed types are tagged with `op`, unknown fields are ignored. */
val LinkJson: Json = Json {
    classDiscriminator = "op"
    ignoreUnknownKeys = true
}

/** The wire name of [this], as a [LinkFrame.Fault] or an HTTP error body carries it. */
val LinkFaultCode.wireName: String
    get() = LinkJson.encodeToJsonElement(LinkFaultCode.serializer(), this).jsonPrimitive.content

/** The WebSocket messages for [frame]: one, or ordered [LinkFrame.Part]s under [partId] when it is too long. */
fun encodeLinkFrame(frame: LinkFrame, partId: () -> Long): List<String> =
    linkFrameMessages(LinkJson.encodeToString(LinkFrame.serializer(), frame), partId)

/** [linkFrameMessages] for a frame already encoded as [text], as a replayed push is. */
fun linkFrameMessages(text: String, partId: () -> Long): List<String> {
    if (text.length <= LinkLimits.MAX_FRAME_CHARS) return listOf(text)
    val id = partId()
    val slices = slices(text)
    return slices.mapIndexed { index, slice ->
        LinkJson.encodeToString(LinkFrame.serializer(), LinkFrame.Part(id, index, index == slices.lastIndex, slice))
    }
}

/**
 * [text] cut into [PART_SLICE_CHARS]-sized slices, never between the halves of a surrogate pair: a text frame carries
 * UTF-8, which cannot encode half a character.
 */
private fun slices(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + PART_SLICE_CHARS, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}

/** A message or reassembled frame over the channel's limits; the channel closes with FRAME_TOO_LARGE. */
class OversizedLinkFrameException(message: String) : IllegalArgumentException(message)

/** Reassembles [LinkFrame.Part]s on one side of a channel. Not thread-safe; each channel reads on one thread. */
class LinkFrameAssembler {
    private val pending = mutableMapOf<Long, StringBuilder>()
    private val nextIndex = mutableMapOf<Long, Int>()

    /** The frame [message] completes, `null` while parts are still missing; throws on an invalid or oversized frame. */
    fun accept(message: String): LinkFrame? {
        bounded(message.length <= LinkLimits.MAX_FRAME_CHARS + PART_OVERHEAD_CHARS) { "The frame is too large." }
        val frame = LinkJson.decodeFromString(LinkFrame.serializer(), message)
        if (frame !is LinkFrame.Part) return frame
        return append(frame)
    }

    /** Adds one part, answering the whole frame once [LinkFrame.Part.last] arrives. */
    private fun append(part: LinkFrame.Part): LinkFrame? {
        val expected = nextIndex[part.partId] ?: 0
        require(part.index == expected) { "Part ${part.index} of ${part.partId} arrived out of order." }
        val buffer = pending.getOrPut(part.partId) { StringBuilder() }.append(part.text)
        bounded(buffer.length <= LinkLimits.MAX_ASSEMBLED_FRAME_CHARS) { "The assembled frame is too large." }
        bounded(pending.size <= MAX_PENDING_PARTS) { "Too many frames are being assembled." }
        if (!part.last) {
            nextIndex[part.partId] = expected + 1
            return null
        }
        pending.remove(part.partId)
        nextIndex.remove(part.partId)
        return LinkJson.decodeFromString(LinkFrame.serializer(), buffer.toString()).also {
            require(it !is LinkFrame.Part) { "A part cannot contain a part." }
        }
    }

    private inline fun bounded(within: Boolean, message: () -> String) {
        if (!within) throw OversizedLinkFrameException(message())
    }
}

private const val PART_OVERHEAD_CHARS = 256
private const val PART_SLICE_CHARS = LinkLimits.MAX_FRAME_CHARS / 2
private const val MAX_PENDING_PARTS = 4
