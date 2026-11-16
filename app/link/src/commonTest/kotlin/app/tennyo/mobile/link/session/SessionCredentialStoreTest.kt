package app.tennyo.mobile.link.session

import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class SessionCredentialStoreTest {
    @Test
    fun malformedSecureRecordIsCleared(): Unit = runTest {
        val storage = FakeSecureStorage("malformed")

        assertEquals(
            StoredSessionReadResult.Corrupt,
            SessionCredentialStore(storage).readSecureActiveSession()
        )
        assertTrue(storage.cleared)
    }

    @Test
    fun writesOnlyCanonicalArtifactRecord(): Unit = runTest {
        val artifact = LinkTicket("https://host", "session", "token", "pin", "Project", "/project")
        val storage = FakeSecureStorage()
        val record = StoredSession(
            artifact = artifact.toStoredArtifact(), sessionId = artifact.sessionId, projectName = artifact.projectName,
            projectPath = artifact.projectPath, startedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )

        assertTrue(SessionCredentialStore(storage).writeSecureActiveSession(record))
        assertTrue(storage.value!!.contains("\"artifact\""))
        assertFalse(storage.value!!.contains("\"token\""))
    }

    @Test
    fun successfulBackgroundPingKeepsCredentialResumablePastOriginalIdleDeadline(): Unit = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val artifact = LinkTicket("https://host", "session", "token", "pin", "Project", "/project")
        val storage = FakeSecureStorage()
        val store = SessionCredentialStore(storage)
        val record = StoredSession(
            artifact = artifact.toStoredArtifact(),
            sessionId = artifact.sessionId,
            projectName = artifact.projectName,
            projectPath = artifact.projectPath,
            startedAtEpochMillis = now,
        )

        assertTrue(store.writeSecureActiveSession(record))
        assertTrue(
            store.recordSuccessfulPing(
                artifact.sessionId,
                now + SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS - 1
            )
        )

        assertTrue(
            store.readSecureActiveSession(
                now + (SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS * 2) - 1,
            ) is StoredSessionReadResult.Found,
        )
    }

    @Test
    fun successfulForegroundPingKeepsCredentialResumableWhenBackgroundingLater(): Unit = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val artifact = LinkTicket("https://host", "session", "token", "pin", "Project", "/project")
        val storage = FakeSecureStorage()
        val store = SessionCredentialStore(storage)
        val record = StoredSession(
            artifact = artifact.toStoredArtifact(),
            sessionId = artifact.sessionId,
            projectName = artifact.projectName,
            projectPath = artifact.projectPath,
            startedAtEpochMillis = now,
        )

        assertTrue(store.writeSecureActiveSession(record))
        val foregroundPingAt = now + SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS - 1
        assertTrue(store.recordSuccessfulPing(artifact.sessionId, foregroundPingAt))

        assertTrue(
            store.readSecureActiveSession(
                foregroundPingAt + SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS - 1,
            ) is StoredSessionReadResult.Found,
        )
    }

    @Test
    fun pingForAnotherSessionDoesNotRefreshTheStoredCredential(): Unit = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val artifact = LinkTicket("https://host", "session", "token", "pin", "Project", "/project")
        val storage = FakeSecureStorage()
        val store = SessionCredentialStore(storage)
        val record = StoredSession(
            artifact = artifact.toStoredArtifact(),
            sessionId = artifact.sessionId,
            projectName = artifact.projectName,
            projectPath = artifact.projectPath,
            startedAtEpochMillis = now,
        )

        assertTrue(store.writeSecureActiveSession(record))
        assertFalse(store.recordSuccessfulPing("another-session", now + 1))
        assertEquals(
            0L,
            (store.readSecureActiveSession(now + 1) as StoredSessionReadResult.Found)
                .record
                .lastSuccessfulPingAtEpochMillis,
        )
    }

    @Test
    fun storedCredentialMatchesOnlyItsOriginalSessionAndBearer() {
        val artifact = LinkTicket("https://host", "session", "token", "pin", "Project", "/project")
        val record = StoredSession(
            artifact = artifact.toStoredArtifact(),
            sessionId = artifact.sessionId,
            projectName = artifact.projectName,
            projectPath = artifact.projectPath,
            startedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )

        assertTrue(record.matches(artifact))
        assertFalse(record.matches(artifact.copy(sessionId = "other-session")))
        assertFalse(record.matches(artifact.copy(token = "other-token")))
    }


    private class FakeSecureStorage(var value: String? = null) : SecureCredentialStorage {
        var cleared = false
        override suspend fun readActive() =
            value?.let(CredentialReadResult::Found) ?: CredentialReadResult.NotFound

        override suspend fun write(record: String): CredentialWriteResult {
            value = record; return CredentialWriteResult.Stored
        }

        override suspend fun clear(): CredentialClearResult {
            cleared = true; value = null; return CredentialClearResult.Cleared
        }
    }
}
