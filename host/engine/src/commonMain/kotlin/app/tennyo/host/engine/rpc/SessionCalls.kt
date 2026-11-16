package app.tennyo.host.engine.rpc

import app.tennyo.contract.ActiveSurface
import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.SessionResponse
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.HostSession

internal fun HostSession.stopFromClient(): LinkResult =
    LinkResult.Done(owner.stopSession(artifact.sessionId, "Remote session ended from the app."))

internal fun HostSession.sessionStats(): LinkResult =
    LinkResult.Stats(stats = stats.stats(), serverTimeEpochMillis = hostWallClockMillis())

/** Applies the shown pane the channel queued on arrival; a newer one queued since makes this one a no-op. */
internal fun HostSession.showPane(call: LinkCall.ShowPane): LinkResult {
    showQueuedSurface(call.toActiveSurface())
    return LinkResult.Done()
}

/** The pane [LinkCall.ShowPane] asks for; a blank selection means none. */
internal fun LinkCall.ShowPane.toActiveSurface(): ActiveSurface =
    ActiveSurface(
        panel = panel,
        selectedFilePath = selectedFilePath?.takeIf { it.isNotBlank() },
        selectedLaunchTabId = selectedLaunchTabId?.takeIf { it.isNotBlank() },
        selectedTerminalId = selectedTerminalId?.takeIf { it.isNotBlank() },
    )

fun HostSession.toSessionResponse(
    requiresPairingConfirmation: Boolean = false,
    pairingChallenge: PairingChallenge? = null,
): SessionResponse =
    SessionResponse(
        sessionId = artifact.sessionId,
        projectName = artifact.projectName,
        projectPath = artifact.projectPath,
        companionId = artifact.companionId,
        hostVersion = artifact.hostVersion,
        editor = surfaces.projectInfo.editor,
        capabilities = capabilities(),
        stats = stats.stats(),
        requiresPairingConfirmation = requiresPairingConfirmation,
        pairingChallenge = pairingChallenge,
        serverTimeEpochMillis = hostWallClockMillis(),
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

fun HostSession.toHandshakeResponse(): HandshakeResponse =
    HandshakeResponse(
        sessionId = artifact.sessionId,
        projectName = artifact.projectName,
        projectPath = artifact.projectPath,
        companionId = artifact.companionId,
        hostVersion = artifact.hostVersion,
        editor = surfaces.projectInfo.editor,
        capabilities = capabilities(),
        serverTimeEpochMillis = hostWallClockMillis(),
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
