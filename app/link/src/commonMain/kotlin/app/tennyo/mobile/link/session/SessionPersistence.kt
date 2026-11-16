package app.tennyo.mobile.link.session

import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.launch

/**
 * Reads and writes the resumable-session record in secure storage, and keeps the persisted fields of
 * [ConnectionStatus] (last applied push, ping timestamps, approved idle timeout) in step with it.
 */
internal class SessionPersistence(
    private val store: SessionCredentialStore,
    private val core: SessionCore,
    private val status: ConnectionStatus,
) {
    suspend fun restore(ticket: LinkTicket) {
        val record = (store.readSecureActiveSession() as? StoredSessionReadResult.Found)?.record ?: return
        if (!record.matches(ticket)) return
        status.update {
            it.copy(
                hasPersistedResumableSession = true,
                lastPushSeq = record.lastPushSeq ?: it.lastPushSeq,
                notificationStartedAtEpochMillis = record.startedAtEpochMillis,
                approvedDeviceIdleTimeoutMillis = record.approvedDeviceIdleTimeoutMillis,
                lastSuccessfulPingAtEpochMillis = record.lastSuccessfulPingAtEpochMillis,
                lastPersistedPingAtEpochMillis = record.lastSuccessfulPingAtEpochMillis,
            )
        }
    }

    fun saveActiveSession(idleKeepAliveActive: Boolean) {
        val ticket = core.ticket.value ?: return
        val project = core.project.value
        core.scope.launch {
            store.writeSecureActiveSession(
                StoredSession(
                    artifact = ticket.toStoredArtifact(),
                    sessionId = ticket.sessionId,
                    projectName = project.name.ifBlank { ticket.projectName },
                    projectPath = project.path.ifBlank { ticket.projectPath },
                    startedAtEpochMillis = status.notificationStartedAtEpochMillis,
                    lastPushSeq = status.lastPushSeq,
                    lastSuccessfulPingAtEpochMillis = status.lastSuccessfulPingAtEpochMillis,
                    approvedDeviceIdleTimeoutMillis = status.approvedDeviceIdleTimeoutMillis,
                    idleKeepAliveActive = idleKeepAliveActive,
                ),
            )
        }
    }

    fun recordSuccessfulPing(sessionId: String, atEpochMillis: Long) {
        core.scope.launch { store.recordSuccessfulPing(sessionId, atEpochMillis) }
    }

    fun clear() {
        core.scope.launch { store.clearSecureActiveSession() }
    }

    fun checkpoint(pushSeq: Long) {
        status.update { it.copy(lastPushSeq = pushSeq) }
    }

    fun clearCheckpoint() {
        status.update { it.copy(lastPushSeq = null) }
    }
}
