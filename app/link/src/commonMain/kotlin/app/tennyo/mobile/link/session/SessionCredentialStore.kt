package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkJson
import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.LinkTicket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** Persists the resumable session credential in platform secure storage. */
class SessionCredentialStore(
    private val secureStorage: SecureCredentialStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun readSecureActiveSession(
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): StoredSessionReadResult =
        when (val result = secureStorage.readActive()) {
            is CredentialReadResult.Found -> {
                val record =
                    runCatching { json.decodeFromString<StoredSession>(result.record) }.getOrNull()
                if (record?.isValidAt(nowEpochMillis) == true) StoredSessionReadResult.Found(record)
                else {
                    secureStorage.clear()
                    StoredSessionReadResult.Corrupt
                }
            }

            CredentialReadResult.NotFound -> StoredSessionReadResult.NotFound
            CredentialReadResult.Unavailable -> StoredSessionReadResult.Unavailable
            CredentialReadResult.Corrupt -> {
                secureStorage.clear()
                StoredSessionReadResult.Corrupt
            }

            CredentialReadResult.IoFailure -> StoredSessionReadResult.IoFailure
        }

    suspend fun writeSecureActiveSession(record: StoredSession): Boolean {
        if (!record.isValidAt(Clock.System.now().toEpochMilliseconds())) return false
        return secureStorage.write(json.encodeToString(record)) == CredentialWriteResult.Stored
    }

    suspend fun clearSecureActiveSession(): Boolean =
        secureStorage.clear() == CredentialClearResult.Cleared

    /** Records host activity so a healthy background keepalive remains resumable after process death. */
    suspend fun recordSuccessfulPing(
        sessionId: String,
        occurredAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        val storedRecord = when (val result = secureStorage.readActive()) {
            is CredentialReadResult.Found ->
                runCatching { json.decodeFromString<StoredSession>(result.record) }.getOrNull()

            else -> null
        } ?: return false
        if (storedRecord.sessionId != sessionId || !storedRecord.isValidAt(occurredAtEpochMillis)) return false
        val refreshedRecord = storedRecord.copy(
            lastSuccessfulPingAtEpochMillis = maxOf(
                storedRecord.lastSuccessfulPingAtEpochMillis,
                occurredAtEpochMillis,
            ),
        )
        return secureStorage.write(json.encodeToString(refreshedRecord)) == CredentialWriteResult.Stored
    }
}

sealed interface StoredSessionReadResult {
    data class Found(val record: StoredSession) : StoredSessionReadResult
    data object NotFound : StoredSessionReadResult
    data object Unavailable : StoredSessionReadResult
    data object Corrupt : StoredSessionReadResult
    data object IoFailure : StoredSessionReadResult
}

@Serializable
data class StoredSession(
    val schemaVersion: Int = 1,
    val artifact: String,
    val sessionId: String,
    val projectName: String,
    val projectPath: String,
    val startedAtEpochMillis: Long,
    val lastPushSeq: Long? = null,
    val lastSuccessfulPingAtEpochMillis: Long = 0L,
    val idleKeepAliveActive: Boolean = false,
    val approvedDeviceIdleTimeoutMillis: Long = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS,
) {
    fun matches(artifact: LinkTicket): Boolean {
        val storedArtifact = this.artifact.toLinkTicket() ?: return false
        return storedArtifact.sessionId == artifact.sessionId && storedArtifact.token == artifact.token
    }

    fun isValidAt(nowEpochMillis: Long): Boolean {
        val stored = artifact.toLinkTicket() ?: return false
        return schemaVersion == 1 && stored.sessionId == sessionId && stored.token.isNotBlank() &&
                startedAtEpochMillis > 0L && approvedDeviceIdleTimeoutMillis > 0L &&
                nowEpochMillis - maxOf(
            startedAtEpochMillis,
            lastSuccessfulPingAtEpochMillis
        ) <= approvedDeviceIdleTimeoutMillis
    }
}

/**
 * The stored form of a ticket: its own JSON. Pairing by name leaves no link anywhere in the product, so a ticket
 * exists only inside the app and its secure storage, and nothing outside ever renders one.
 */
fun LinkTicket.toStoredArtifact(): String = LinkJson.encodeToString(LinkTicket.serializer(), this)

fun String.toLinkTicket(): LinkTicket? =
    runCatching { LinkJson.decodeFromString(LinkTicket.serializer(), this) }.getOrNull()
