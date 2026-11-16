package app.tennyo.host.engine.session

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.contract.SessionEndReason
import app.tennyo.crypto.constantTimeEquals
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.auth.CommandStream
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.panes.ProjectPanes
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.debugQuotedOrNone
import app.tennyo.host.engine.platform.debugShortId
import app.tennyo.host.engine.platform.debugShortIdOrNone
import app.tennyo.host.engine.platform.discoveryDebug
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.platform.hostBlockingDispatcher
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.rpc.CallFault
import app.tennyo.host.engine.rpc.handle
import app.tennyo.host.engine.rpc.handleInOrder
import app.tennyo.host.engine.rpc.runsInOrder
import app.tennyo.host.engine.rpc.sessionExpirationMessage
import app.tennyo.host.engine.rpc.toActiveSurface
import app.tennyo.host.engine.rpc.waitsOnAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The protocol half of one project's host: its sessions, the channel open for each, the push history a channel resumes
 * from, and remembered-pair bookkeeping. The IDE side reaches the user and the listener through [platform] and the
 * project through [surfaces].
 */
@Suppress("TooManyFunctions")
class SessionHost(
    val surfaces: ProjectPanes,
    val platform: HostPlatform,
    private val scope: CoroutineScope,
    private val wallClockMillis: () -> Long = ::hostWallClockMillis,
) {
    private val sessions = HostConcurrentMap<String, HostSession>()
    private val channels = HostConcurrentMap<String, LinkChannel>()
    private val pushHistories = HostConcurrentMap<String, PushHistory>()
    private val rememberedPairSummariesCache = AtomicReference<List<RememberedPairSummary>>(emptyList())
    private val pushSeq = AtomicLong(0L)

    // Pushes and channel opens take this lock, so an opening channel sees each push either in its replay or live.
    private val pushLock = HostMonitor()
    private val lifecycleLock = HostMonitor()
    private val concurrentCalls = Semaphore(MAX_CONCURRENT_CALLS)

    // An agent can take minutes to answer, or never; its calls wait in their own pool so the panes keep answering.
    private val agentCalls = Semaphore(MAX_CONCURRENT_CALLS)
    private val activeSessionId = AtomicReference<String?>(null)
    private val startingSession = AtomicBoolean(false)
    private val expiryMonitors: List<Job>

    /** Whether this host is open to devices it has never met, and under what name; see [openPairingWindow]. */
    val pairingWindow = PairingWindow(wallClockMillis)

    val isStartingSession: Boolean get() = startingSession.load()

    val sessionCount: Int get() = sessions.size

    init {
        expiryMonitors = listOf(
            expiryMonitor(IDLE_EXPIRY_CHECK_SECONDS) { session -> session.nonAbsoluteExpirationReason() },
            expiryMonitor(ABSOLUTE_EXPIRY_CHECK_SECONDS) { session -> session.absoluteExpirationReason() },
        )
        scheduleRememberedPairSummaryRefresh()
    }

    // An already running session answers on its own; the broad catch releases the listener the start had taken.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun startSession(): SessionTicket {
        activeSession()?.let { return it.artifact }
        lifecycleLock.guarded {
            activeSessionId.load()?.let { sessionId ->
                sessions[sessionId]?.let { return it.artifact }
            }
            if (startingSession.load()) {
                error("A Tennyo session is already starting.")
            }
            startingSession.store(true)
        }

        var sessionStarted = false
        var listenerLease: ListenerLease? = null
        try {
            val lease = platform.acquireListener(ownerKind = "session").also { listenerLease = it }
            val endpoints = lease.endpoints()
            val sessionId = hostRandomUuid()
            val projectInfo = surfaces.projectInfo
            val artifact = SessionTicket(
                endpoint = endpoints.primary,
                sessionId = sessionId,
                token = secureRandomBytes(SESSION_TOKEN_BYTES).toBase64Url(),
                fingerprint = endpoints.transportFingerprint,
                projectName = projectInfo.name,
                projectPath = projectInfo.displayPath,
                companionId = platform.companionId(),
                hostVersion = projectInfo.hostVersion,
                capabilities = surfaces.capabilities(),
                alternateEndpoints = endpoints.ticketAlternates(),
            )
            val session = HostSession(artifact, this, scope, lease, wallClockMillis)
            lifecycleLock.guarded {
                sessions[sessionId] = session
                activeSessionId.store(sessionId)
                sessionStarted = true
                listenerLease = null // Session now owns and releases this exact lease.
            }
            platform.onSessionsActiveChanged(true)
            platform.onSessionStateChanged()
            LOG.info(
                "Started Tennyo session $sessionId for project ${projectInfo.name}; " +
                        "secureEndpoints=${1 + endpoints.alternates.size}"
            )
            return artifact
        } catch (throwable: Throwable) {
            if (!sessionStarted) listenerLease?.release()
            throw throwable
        } finally {
            startingSession.store(false)
        }
    }

    fun activeSession(): HostSession? {
        val sessionId = activeSessionId.load() ?: return null
        val session = sessions[sessionId]
        if (session == null) {
            clearActiveSession(sessionId)
            return null
        }
        if (session.hasApprovedDeviceTimedOut()) {
            stopIdleSession(sessionId)
            return null
        }
        return session
    }

    fun session(sessionId: String): HostSession? = sessions[sessionId]

    fun isClientConnected(sessionId: String): Boolean {
        if (channels.containsKey(sessionId)) return true
        val session = sessions[sessionId] ?: return false
        return session.auth.hasRecentlySeenApprovedDevice(session.monotonicNow())
    }

    fun connectedDeviceName(sessionId: String): String? = sessions[sessionId]?.auth?.approvedDeviceName()

    fun connectedDeviceId(sessionId: String): String? = sessions[sessionId]?.auth?.approvedDeviceKeyHash()

    fun rememberedPairs(): List<RememberedPairSummary> =
        rememberedPairSummariesCache.load().also {
            if (it.isEmpty()) {
                scheduleRememberedPairSummaryRefresh()
            }
        }

    fun revokeRememberedPair(pairId: String): Boolean =
        platform.pairStore.revoke(pairId).also { revoked ->
            LOG.discoveryDebug { "revoke remembered pair pair=${pairId.debugShortId()} revoked=$revoked" }
            if (revoked) {
                rememberedPairSummariesCache.updateAndFetch { summaries -> summaries.filterNot { it.pairId == pairId } }
                scheduleRememberedPairSummaryRefresh()
            }
        }

    fun saveRememberedPair(record: RememberedPairRecord) {
        platform.pairStore.save(record)
        scheduleRememberedPairSummaryRefresh()
        LOG.discoveryDebug {
            "saved remembered pair pair=${record.pairId.debugShortId()} " +
                    "companion=${record.companionId.debugShortIdOrNone()} " +
                    "device=${record.deviceLabel.debugQuotedOrNone()}"
        }
    }

    fun findRememberedPair(pairId: String): RememberedPairRecord? {
        val record = platform.pairStore.find(pairId)
        if (record != null && record.companionId != platform.companionId()) {
            platform.pairStore.revoke(pairId)
            scheduleRememberedPairSummaryRefresh()
            LOG.discoveryDebug {
                "lookup remembered pair pair=${pairId.debugShortId()} found=false companion=<stale>"
            }
            return null
        }
        LOG.discoveryDebug {
            "lookup remembered pair pair=${pairId.debugShortId()} found=${record != null} " +
                    "companion=${record?.companionId.debugShortIdOrNone()}"
        }
        return record
    }

    fun replaceRememberedPair(
        pairId: String,
        expectedPairSecret: String,
        replacement: RememberedPairRecord,
    ): Boolean =
        platform.pairStore.replace(
            pairId = pairId,
            expectedPairSecret = expectedPairSecret,
            replacement = replacement,
        ).also { replaced ->
            if (replaced) {
                scheduleRememberedPairSummaryRefresh()
            }
            LOG.discoveryDebug {
                "replace remembered pair pair=${pairId.debugShortId()} " +
                        "replacement=${replacement.pairId.debugShortId()} " +
                        "companion=${replacement.companionId.debugShortIdOrNone()} replaced=$replaced"
            }
        }

    fun stopSession(
        sessionId: String,
        reason: String,
        endReason: SessionEndReason? = null,
    ): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        clearActiveSession(sessionId)
        // The window belongs to this session; leaving it open would advertise a token that can no longer claim.
        if (pairingWindow.current()?.pairingSessionId == sessionId) pairingWindow.close()
        LOG.info("Stopping Tennyo session $sessionId: $reason")
        push(sessionId, LinkPush.SessionEnded(reason = reason, reasonCode = endReason))
        session.close()
        channels.remove(sessionId)?.let { channel ->
            scope.launch {
                // Lets the SessionEnded push flush before the socket goes.
                delay(SESSION_ENDED_CLOSE_DELAY_MILLIS.milliseconds)
                channel.close(LinkChannel.NORMAL_CLOSURE, reason)
            }
        }
        platform.expireConnectionRequests(sessionId)
        pushHistories.remove(sessionId)
        platform.onSessionsActiveChanged(sessions.isNotEmpty())
        platform.onSessionStateChanged()
        return true
    }

    fun pendingConnectionRequests(sessionId: String): List<ConnectionRequest> =
        sessions[sessionId]?.auth?.pendingConnectionRequests().orEmpty()

    fun acceptConnectionRequest(sessionId: String, requestId: String): Boolean =
        sessions[sessionId]?.auth?.acceptConnectionRequest(requestId) == true

    fun rejectConnectionRequest(sessionId: String, requestId: String): Boolean =
        sessions[sessionId]?.auth?.rejectConnectionRequest(requestId) == true

    /** The live session [sessionId] names, if [token] is its token; a session found idle past its deadline is ended. */
    fun validate(sessionId: String?, token: String?): HostSession? {
        if (sessionId.isNullOrBlank() || token.isNullOrBlank()) return null
        val session = sessions[sessionId]?.takeIf { constantTimeEquals(it.artifact.token, token) } ?: return null
        if (session.hasApprovedDeviceTimedOut()) {
            stopIdleSession(sessionId)
            return null
        }
        return session
    }

    /**
     * Opens [channel] for [session] once the approved device's proof over [open] verifies: sends [LinkFrame.Opened],
     * then the pushes the client missed after [LinkFrame.Open.lastPushSeq], then the current state of what it shows. A
     * channel already open for the session is superseded. Returns why the open is refused, if it is.
     */
    fun openChannel(channel: LinkChannel, session: HostSession, open: LinkFrame.Open): LinkFaultCode? {
        val sessionId = session.artifact.sessionId
        val refusal = session.expirationReason()?.let { reason ->
            stopSession(sessionId, sessionExpirationMessage(reason), reason)
            LinkFaultCode.SESSION_EXPIRED
        } ?: session.auth.verifyApprovedProof(LinkEndpoints.CHANNEL, open.proofRequestText, open.proof)
        if (refusal != null) return refusal
        val stream = CommandStream(
            ticket = session.artifact,
            streamId = secureRandomBytes(COMMAND_STREAM_ID_BYTES).toBase64Url(),
            approvedDeviceFingerprint = open.proof.deviceFingerprint,
            commandKeySeed = secureRandomBytes(COMMAND_KEY_SEED_BYTES),
        )
        val binding = ChannelBinding(
            host = this,
            session = session,
            stream = stream,
            orderedCalls = OrderedCallQueue(
                schedule = { drain -> platform.executeInBackground(drain) },
                maxPendingCommands = MAX_PENDING_ORDERED_CALLS,
            ),
        )
        if (open.lastPushSeq != null) session.stats.recordReconnect()
        val superseded = pushLock.guarded {
            val resume = pushHistory(sessionId).resumeAfter(open.lastPushSeq)
            channel.bind(binding)
            channel.send(
                LinkFrame.Opened(
                    streamId = stream.streamId,
                    commandKeySeed = stream.commandKeySeedBase64,
                    serverTimeEpochMillis = wallClockMillis(),
                    snapshotRequired = resume.snapshotRequired,
                ),
            )
            resume.missed.forEach { push -> channel.sendEncoded(push.frameText) }
            channels.put(sessionId, channel)
        }
        if (superseded != null && superseded != channel) {
            superseded.close(LinkChannel.NORMAL_CLOSURE, "Superseded by a new channel.")
        }
        LOG.info("Tennyo channel opened for session $sessionId; resumeAfter=${open.lastPushSeq}")
        session.sessionScope.launch {
            runCatching { session.emitConvergenceSnapshot() }
                .onFailure { LOG.info("Unable to push the convergence snapshot", it) }
        }
        platform.onSessionStateChanged()
        return null
    }

    /** Verifies one call and runs it: in arrival order for calls whose order matters, concurrently for the rest. */
    fun handleCall(channel: LinkChannel, binding: ChannelBinding, frame: LinkFrame.Call, messageChars: Int) {
        val session = binding.session
        val expired = session.expirationReason()
        if (expired != null) {
            stopSession(session.artifact.sessionId, sessionExpirationMessage(expired), expired)
            return
        }
        val refusal = binding.stream.verify(frame)
        if (refusal != null) {
            LOG.debug { "Refused a call on stream ${binding.stream.streamId}: $refusal" }
            return channel.fail(refusal, callId = frame.id)
        }
        session.auth.markApprovedDeviceSeen()
        session.stats.recordUploadBytes(messageChars.toLong())
        val call = frame.call
        if (!call.runsInOrder) {
            session.sessionScope.launch {
                (if (call.waitsOnAgent) agentCalls else concurrentCalls).withPermit {
                    withContext(hostBlockingDispatcher) { reply(channel, session, frame.id) { session.handle(call) } }
                }
            }
            return
        }
        if (call is LinkCall.ShowPane) session.activeSurface.queue(call.toActiveSurface())
        val accepted = binding.orderedCalls.submit {
            reply(channel, session, frame.id) { session.handleInOrder(call) }
        }
        if (!accepted) channel.fail(LinkFaultCode.BUSY, "Too many calls are waiting.", frame.id)
    }

    fun detachChannel(channel: LinkChannel) {
        val sessionId = channels.entries().firstOrNull { it.second == channel }?.first ?: return
        if (!channels.remove(sessionId, channel)) return
        LOG.info("Tennyo channel closed for session $sessionId")
        sessions[sessionId]?.emitStats()
        platform.onSessionStateChanged()
    }

    /** Numbers [push], keeps it for resuming clients, and sends it to the session's channel. */
    fun push(sessionId: String, push: LinkPush) {
        pushLock.guarded {
            val seq = pushSeq.addAndFetch(1L)
            val frameText = LinkJson.encodeToString(LinkFrame.serializer(), LinkFrame.Push(seq, push))
            pushHistory(sessionId).append(SentPush(seq, frameText))
            val channel = channels[sessionId] ?: return@guarded
            sessions[sessionId]?.stats?.recordDownloadBytes(frameText.length.toLong())
            channel.sendEncoded(frameText)
        }
    }

    fun broadcastLaunchOutput(configurationId: String, text: String, isRunning: Boolean, tabId: String? = null) {
        sessionsWith(Capability.LAUNCH_CONFIGURATIONS).forEach { session ->
            session.launch.emitLaunchOutput(configurationId, text, isRunning, tabId)
        }
    }

    /** Flushes coalesced launch output; from the UI thread the flush moves to the background, since it blocks. */
    fun flushLaunchOutput(tabId: String? = null) {
        val flush = { sessions.values().forEach { it.launch.flushLaunchOutput(tabId) } }
        if (platform.isUiThread()) {
            platform.executeInBackground(flush)
        } else {
            flush()
        }
    }

    fun broadcastEditorTabs() {
        sessionsWith(Capability.FILES).forEach { it.editor.emitEditorTabs() }
    }

    fun broadcastFileContent(path: String) {
        sessionsWith(Capability.FILES).forEach { it.editor.emitFileContent(path) }
    }

    fun broadcastLaunchConfigurations() {
        sessionsWith(Capability.LAUNCH_CONFIGURATIONS).forEach { session ->
            session.launch.emitLaunchConfigurations()
        }
    }

    fun broadcastLaunchTabs() {
        sessionsWith(Capability.LAUNCH_CONFIGURATIONS).forEach { session ->
            session.launch.emitLaunchTabs()
        }
    }

    /** Stops the expiry monitors and every session, as when the project closes. */
    fun close() {
        expiryMonitors.forEach { it.cancel() }
        sessions.keys().forEach { sessionId ->
            runCatching {
                stopSession(sessionId, "The project was closed on the computer.")
            }.onFailure {
                LOG.warn("Unable to gracefully stop Tennyo session $sessionId during project close", it)
            }
        }
    }

    private inline fun reply(channel: LinkChannel, session: HostSession, callId: Long, run: () -> LinkResult) {
        val frame = answer(callId, run)
        if (channel.send(frame)) {
            session.stats.recordDownloadBytes(LinkJson.encodeToString(LinkFrame.serializer(), frame).length.toLong())
        }
    }

    private fun stopIdleSession(sessionId: String) {
        stopSession(sessionId, sessionExpirationMessage(SessionEndReason.IdleExpired), SessionEndReason.IdleExpired)
    }

    private fun pushHistory(sessionId: String): PushHistory =
        pushHistories.computeIfAbsent(sessionId) { PushHistory(MAX_REPLAY_PUSHES, MAX_REPLAY_CHARS) }

    private fun sessionsWith(capability: Capability): List<HostSession> =
        sessions.values().filter { it.hasCapability(capability) }

    private fun expiryMonitor(
        periodSeconds: Long,
        expirationReason: (HostSession) -> SessionEndReason?,
    ): Job = scope.launch {
        while (true) {
            delay(periodSeconds.seconds)
            runCatching { stopExpiredSessions(expirationReason) }
                .onFailure { LOG.warn("Unable to stop expired Tennyo sessions", it) }
        }
    }

    private fun scheduleRememberedPairSummaryRefresh() {
        platform.executeInBackground {
            val summaries = platform.pairStore.summaries()
            val stalePairIds = staleRememberedPairIds(platform.companionId(), summaries)
            stalePairIds.forEach(platform.pairStore::revoke)
            rememberedPairSummariesCache.store(summaries.filterNot { it.pairId in stalePairIds })
        }
    }

    private fun clearActiveSession(sessionId: String) {
        lifecycleLock.guarded {
            if (activeSessionId.load() == sessionId) {
                activeSessionId.store(null)
            }
        }
    }

    private fun stopExpiredSessions(expirationReason: (HostSession) -> SessionEndReason?) {
        sessions.values()
            .mapNotNull { session -> expirationReason(session)?.let { reason -> session to reason } }
            .forEach { (session, reason) ->
                stopSession(session.artifact.sessionId, sessionExpirationMessage(reason), reason)
            }
    }

    companion object {
        private val LOG = HostLogger("SessionHost")

        private const val MAX_REPLAY_PUSHES = 200
        private const val MAX_REPLAY_CHARS = 2L * 1024L * 1024L
        private const val MAX_CONCURRENT_CALLS = 4
        private const val IDLE_EXPIRY_CHECK_SECONDS = 5L
        const val ABSOLUTE_EXPIRY_CHECK_SECONDS = 30L
        private const val SESSION_ENDED_CLOSE_DELAY_MILLIS = 250L
        private const val SESSION_TOKEN_BYTES = 32
        private const val COMMAND_KEY_SEED_BYTES = 32
        private const val COMMAND_STREAM_ID_BYTES = 16
        private const val MAX_PENDING_ORDERED_CALLS = 256

        fun staleRememberedPairIds(
            currentCompanionId: String,
            summaries: List<RememberedPairSummary>,
        ): Set<String> =
            summaries
                .asSequence()
                .filter { it.companionId != currentCompanionId }
                .map { it.pairId }
                .toSet()
    }
}

fun latestSuccessfulRemoteConnectionAt(
    companionId: String,
    pairs: List<RememberedPairSummary>,
): Long =
    pairs
        .asSequence()
        .filter { it.companionId == companionId }
        .maxOfOrNull(RememberedPairSummary::lastUsedAtEpochMillis)
        ?: 0L

// A call that fails for any reason still owes the device a frame, so every throwable becomes a fault.
@Suppress("TooGenericExceptionCaught")
private inline fun answer(callId: Long, run: () -> LinkResult): LinkFrame =
    try {
        LinkFrame.Reply(callId, run())
    } catch (timedOut: TimeoutCancellationException) {
        // A timeout inside the call, not the session ending: the call gave up, and the device still waits.
        CALL_LOG.warn("A Tennyo call timed out", timedOut)
        LinkFrame.Fault(callId, LinkFaultCode.OPERATION_FAILED, "The call timed out.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fault: CallFault) {
        LinkFrame.Fault(callId, fault.code, fault.message)
    } catch (throwable: Throwable) {
        CALL_LOG.warn("Unable to handle a Tennyo call", throwable)
        LinkFrame.Fault(callId, LinkFaultCode.OPERATION_FAILED, "The call could not be completed.")
    }

private val CALL_LOG = HostLogger("SessionHost")
