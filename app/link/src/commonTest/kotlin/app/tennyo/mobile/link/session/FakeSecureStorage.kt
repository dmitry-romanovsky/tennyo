package app.tennyo.mobile.link.session

internal class FakeSecureStorage(var value: String? = null) : SecureCredentialStorage {
    override suspend fun readActive() =
        value?.let(CredentialReadResult::Found) ?: CredentialReadResult.NotFound

    override suspend fun write(record: String): CredentialWriteResult {
        value = record
        return CredentialWriteResult.Stored
    }

    override suspend fun clear(): CredentialClearResult {
        value = null
        return CredentialClearResult.Cleared
    }
}
