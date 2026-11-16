package app.tennyo.mobile.link.session

/** Platform-owned storage for the single credential needed to resume a remote session. */
interface SecureCredentialStorage {
    suspend fun readActive(): CredentialReadResult
    suspend fun write(record: String): CredentialWriteResult
    suspend fun clear(): CredentialClearResult
}

sealed interface CredentialReadResult {
    data class Found(val record: String) : CredentialReadResult
    data object NotFound : CredentialReadResult
    data object Unavailable : CredentialReadResult
    data object Corrupt : CredentialReadResult
    data object IoFailure : CredentialReadResult
}

sealed interface CredentialWriteResult {
    data object Stored : CredentialWriteResult
    data object Unavailable : CredentialWriteResult
    data object IoFailure : CredentialWriteResult
}

sealed interface CredentialClearResult {
    data object Cleared : CredentialClearResult
    data object Unavailable : CredentialClearResult
    data object IoFailure : CredentialClearResult
}
