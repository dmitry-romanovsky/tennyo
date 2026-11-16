package app.tennyo.mobile.workspace

import app.tennyo.mobile.link.session.CredentialClearResult
import app.tennyo.mobile.link.session.CredentialReadResult
import app.tennyo.mobile.link.session.CredentialWriteResult
import app.tennyo.mobile.link.session.SecureCredentialStorage
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.session.SessionNotificationInfo
import kotlinx.coroutines.flow.MutableSharedFlow

internal class FakeSessionNotification : SessionNotification {
    override val endSessionRequests: MutableSharedFlow<String> = MutableSharedFlow()
    override fun start(info: SessionNotificationInfo) = Unit
    override fun update(info: SessionNotificationInfo) = Unit
    override fun endSession() = Unit
    override fun stop(sessionId: String) = Unit
}

internal class FakeSecureStorage(var value: String? = null) : SecureCredentialStorage {
    override suspend fun readActive() =
        value?.let(CredentialReadResult::Found) ?: CredentialReadResult.NotFound

    override suspend fun write(record: String): CredentialWriteResult {
        value = record; return CredentialWriteResult.Stored
    }

    override suspend fun clear(): CredentialClearResult {
        value = null; return CredentialClearResult.Cleared
    }
}
