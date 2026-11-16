package app.tennyo.mobile.link.session

import app.tennyo.contract.Capability
import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.HostEditor
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.RememberedPairResumeStatus
import app.tennyo.contract.SessionResponse
import app.tennyo.contract.SessionStats
import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.ConnectStep
import app.tennyo.mobile.link.DegradedReason
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.LiveSync
import app.tennyo.mobile.link.PairingConfirmation
import app.tennyo.mobile.link.PairingIssue
import app.tennyo.mobile.link.hasUsableWordGrid
import app.tennyo.mobile.link.pairing.FirstPairing
import app.tennyo.mobile.link.pairing.HelloResult
import app.tennyo.mobile.link.pairing.RememberedPairResume
import app.tennyo.mobile.link.pairing.SessionEndpoints
import app.tennyo.mobile.link.runCatchingCancellable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Establishes the session: picking up the one this device holds, or claiming a new one — through a remembered pair
 * when the host has one on file, or the pairing words otherwise — and then opening the channel.
 */
@Suppress("LongParameterList")
internal class Connector(
    private val core: SessionCore,
    private val status: ConnectionStatus,
    private val endpoints: SessionEndpoints,
    private val firstPairing: FirstPairing,
    private val rememberedPairResume: RememberedPairResume,
    private val persistence: SessionPersistence,
    private val notification: NotificationBridge,
    private val signals: LifecycleSignals,
    private val startLiveSync: (LinkTicket) -> Unit,
) {
    private var connectJob: Job? = null

    fun connect(resume: Boolean) {
        val ticket = core.ticket.value ?: return
        connectJob?.cancel()
        connectJob = core.scope.launch {
            announceAttempt(ticket, resume)
            runCatchingCancellable { establish(ticket, resume) }
                .onSuccess { response -> response?.let { complete(it, core.ticket.value ?: ticket) } }
                .onFailure(signals::handleConnectionFailure)
        }
    }

    fun cancel() {
        connectJob?.cancel()
    }

    private fun announceAttempt(ticket: LinkTicket, resume: Boolean) {
        val afterLoss = resume && core.state.value is LinkState.Degraded
        notification.update(
            status = when {
                !resume -> SessionStatus.CheckingReachability
                afterLoss -> SessionStatus.Reconnecting
                else -> SessionStatus.Connected
            },
            projectName = ticket.projectName,
        )
        signals.startConnectionTimeout()
        core.linkState = when {
            !resume -> LinkState.Connecting(ConnectStep.Reaching)
            afterLoss -> LinkState.Degraded(DegradedReason.Reconnecting)
            // Back from the background the workspace keeps showing the session while live sync re-attaches.
            core.state.value is LinkState.Live -> core.state.value
            else -> LinkState.Connecting(ConnectStep.Resuming)
        }
    }

    /**
     * The admitted session, or `null` when the claim now waits on the pairing words. Resuming only needs the session
     * to still be there: the channel proves this device when it opens, and a host that no longer approves it makes the
     * attachment ask for a fresh claim.
     */
    private suspend fun establish(ticket: LinkTicket, resume: Boolean): AdmittedSession? {
        if (ticket.isPairingWindow) return pairByName(ticket)
        val hello = hello(ticket)
        if (resume) return AdmittedSession(hello.response)
        notification.update(SessionStatus.WaitingForApproval, projectName = hello.response.projectName)
        core.linkState = LinkState.Connecting(ConnectStep.AwaitingApproval)
        val response = endpoints.claim(hello.ticket)
        if (!response.requiresPairingConfirmation) return AdmittedSession(response)
        val challenge = response.pairingChallenge
            ?: throw LinkFault.Refused(0, "pairing_challenge_missing", "The computer sent no pairing challenge.")
        return resumeOrAskForWords(hello.ticket, hello.response.projectName, challenge)
    }

    /**
     * Pairing by name starts at the claim: the window's credential is refused by hello on purpose, and the offer
     * already named the endpoint and the certificate this attempt is pinned to. The project stays unnamed until the
     * words are tapped, which is the host's point in withholding it.
     */
    private suspend fun pairByName(ticket: LinkTicket): AdmittedSession? {
        notification.update(SessionStatus.WaitingForApproval, projectName = "")
        core.linkState = LinkState.Connecting(ConnectStep.AwaitingApproval)
        val response = endpoints.claim(ticket)
        val challenge = response.pairingChallenge
            ?: throw LinkFault.Refused(0, "pairing_challenge_missing", "The computer sent no pairing challenge.")
        return resumeOrAskForWords(ticket, response.projectName, challenge)
    }

    /** Reaches the host and publishes what it says about the project. */
    private suspend fun hello(ticket: LinkTicket): HelloResult {
        val hello = endpoints.hello(ticket)
        core.ticket.value = hello.ticket
        // Checked before anything is signed, so a version gap reads as a version gap rather than surfacing later as
        // an invalid device proof.
        if (hello.response.securityVersion != ProtocolVersions.SECURITY_VERSION) {
            throw LinkFault.ProtocolMismatch(hello.response.securityVersion)
        }
        core.project.value = LinkProject(
            name = hello.response.projectName,
            path = hello.response.projectPath,
            capabilities = hello.response.capabilities,
            editor = hello.response.editor,
        )
        return hello
    }

    /** Answers the pairing challenge with a remembered pair when one works, and otherwise shows the pairing words. */
    private suspend fun resumeOrAskForWords(
        ticket: LinkTicket,
        projectName: String,
        challenge: PairingChallenge,
    ): AdmittedSession? {
        status.update { it.copy(pendingSecurityTicket = ticket) }
        val resumed = runCatchingCancellable { rememberedPairResume.resume(ticket, challenge) }
            .onFailure { Logger.w("Remembered-pair resume failed; falling back to the pairing words", it) }
            .getOrNull()
        if (resumed?.status == RememberedPairResumeStatus.Success) {
            return AdmittedSession(
                resumed.session ?: throw LinkFault.Refused(
                    status = 0,
                    code = "pairing_session_missing",
                    message = "The computer accepted the pair without a session.",
                ),
            )
        }
        val activeChallenge = resumed?.pairingChallenge ?: challenge
        // A malformed grid would render a screen whose Continue button can never enable, leaving the user stuck.
        if (!activeChallenge.hasUsableWordGrid()) throw LinkFault.PairingGridUnusable()
        notification.update(SessionStatus.ConfirmPairing, projectName = projectName)
        signals.restartPairingConfirmationTimeout(activeChallenge)
        core.linkState = LinkState.Pairing(PairingConfirmation(activeChallenge))
        return null
    }

    fun togglePairingWord(word: String) {
        // Taps while an attempt is in flight are dropped: the selection has already been sent.
        core.updatePairing { current ->
            if (current.confirmation.isConfirming) current
            else LinkState.Pairing(current.confirmation.toggled(word))
        }
    }

    fun clearPairingWords() {
        core.updatePairing { current ->
            LinkState.Pairing(current.confirmation.copy(selectedWords = emptyList(), issue = null))
        }
    }

    fun confirmPairing() {
        val confirmation = (core.state.value as? LinkState.Pairing)?.confirmation ?: return
        val ticket = status.pendingSecurityTicket ?: core.ticket.value ?: return
        core.scope.launch {
            notification.update(SessionStatus.ConfirmingPairing)
            core.updatePairing {
                LinkState.Pairing(it.confirmation.copy(isConfirming = true, issue = null))
            }
            runCatchingCancellable { firstPairing.confirm(ticket, confirmation.selectedWords, confirmation.challenge) }
                .onSuccess { confirmation ->
                    val response = confirmation.response
                    when (response.status) {
                        PairingConfirmationStatus.Success -> {
                            val session = response.session
                            // A device that paired by name carries the window's credential in, and the real ticket
                            // out: the session runs under whatever the confirmation left it holding.
                            val admitted = confirmation.ticket
                            if (session == null || admitted == null) {
                                showPairingIssue(PairingIssue.ConfirmationFailed)
                            } else {
                                complete(AdmittedSession(session), admitted)
                            }
                        }

                        PairingConfirmationStatus.IncorrectSelection -> {
                            response.pairingChallenge?.let(signals::restartPairingConfirmationTimeout)
                            // Clearing the selection is not cosmetic: leaving four tiles lit with Continue enabled
                            // invites the user to spend attempt two resubmitting the same answer.
                            core.updatePairing {
                                LinkState.Pairing(
                                    it.confirmation.copy(
                                        challenge = response.pairingChallenge ?: it.confirmation.challenge,
                                        selectedWords = emptyList(),
                                        isConfirming = false,
                                        issue = PairingIssue.IncorrectSelection,
                                    ),
                                )
                            }
                        }

                        PairingConfirmationStatus.StoppedServerExceededMaximumAttempts ->
                            signals.showPairingStoppedAfterTooManyAttempts()
                    }
                }
                .onFailure { throwable ->
                    Logger.w("Pairing confirmation failed", throwable)
                    if (throwable is LinkFault.SessionEnded) {
                        signals.showPairingWordsExpired()
                    } else {
                        showPairingIssue(
                            throwable.message?.takeIf { it.isNotBlank() }?.let(PairingIssue::Reported)
                                ?: PairingIssue.ConfirmationFailed,
                        )
                    }
                }
        }
    }

    private fun showPairingIssue(issue: PairingIssue) {
        core.updatePairing { LinkState.Pairing(it.confirmation.copy(isConfirming = false, issue = issue)) }
    }

    /** The host admitted this device: record the session and open the channel. */
    private suspend fun complete(response: AdmittedSession, ticket: LinkTicket) {
        val connectedAt = Clock.System.now().toEpochMilliseconds()
        status.update {
            it.copy(
                reconnectAttempts = 0,
                channelLossStartedAtEpochMillis = null,
                sessionConnectedInMemory = true,
                hasPersistedResumableSession = false,
                lastSuccessfulPingAtEpochMillis = connectedAt,
                lastPersistedPingAtEpochMillis = connectedAt,
                pendingSecurityTicket = null,
                approvedDeviceIdleTimeoutMillis = response.approvedDeviceIdleTimeoutMillis
                    .takeIf { timeout -> timeout > 0L }
                    ?: SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS,
            )
        }
        signals.clearConnectionTimeout()
        core.ticket.value = ticket
        notification.update(SessionStatus.Connected, projectName = response.projectName)
        core.project.value = LinkProject(
            name = response.projectName,
            path = response.projectPath,
            capabilities = response.capabilities,
            editor = response.editor,
        )
        response.stats?.let { stats ->
            core.stats.value = stats.withLocalSessionStats(status, previous = core.stats.value)
        }
        core.linkState = LinkState.Live(LiveSync.Attaching)
        persistence.saveActiveSession(idleKeepAliveActive = false)
        notification.stopIdleKeepAlive()
        startLiveSync(ticket)
    }
}

/** What a hello, a claim or a pairing confirmation tells the app about the session it may now open a channel to. */
internal class AdmittedSession(
    val projectName: String,
    val projectPath: String,
    val capabilities: Set<Capability>,
    val editor: HostEditor?,
    val approvedDeviceIdleTimeoutMillis: Long,
    val stats: SessionStats?,
) {
    constructor(response: SessionResponse) : this(
        projectName = response.projectName,
        projectPath = response.projectPath,
        capabilities = response.capabilities,
        editor = response.editor,
        approvedDeviceIdleTimeoutMillis = response.approvedDeviceIdleTimeoutMillis,
        stats = response.stats,
    )

    constructor(response: HandshakeResponse) : this(
        projectName = response.projectName,
        projectPath = response.projectPath,
        capabilities = response.capabilities,
        editor = response.editor,
        approvedDeviceIdleTimeoutMillis = response.approvedDeviceIdleTimeoutMillis,
        stats = null,
    )
}

