package app.tennyo.mobile.session

import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.workspace.FakeSecureStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleKeepAliveTest {
    private val terminalFailures = mutableListOf<String>()
    private var timeouts = 0

    private fun TestScope.keepAlive(pings: ArrayDeque<IdlePingResult>) = IdleKeepAlive(
        scope = backgroundScope,
        sessionStore = SessionCredentialStore(FakeSecureStorage()),
        ping = { pings.removeFirst() },
        onStatus = { _, _ -> },
        onTerminalFailure = { _, _, message -> terminalFailures += message },
        onTimedOut = { _, _, _ -> timeouts += 1 },
    )

    @Test
    fun terminalPingFailureEndsTheSessionOnceWithoutTimingOut() = runTest {
        val keepAlive = keepAlive(ArrayDeque(listOf(IdlePingResult.TerminalFailure("revoked"))))

        keepAlive.start(artifactRaw = "artifact", startId = 1)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(listOf("revoked"), terminalFailures)
        assertEquals(0, timeouts)
        assertEquals("", keepAlive.activeArtifact)
    }

    @Test
    fun transientFailureFollowedBySuccessKeepsTheSessionAlive() = runTest {
        val pings = ArrayDeque(
            listOf(
                IdlePingResult.TransientFailure(IllegalStateException("offline")),
                IdlePingResult.Success,
            ),
        )
        val keepAlive = keepAlive(pings)

        keepAlive.start(artifactRaw = "artifact", startId = 1)
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(emptyList<String>(), terminalFailures)
        assertEquals(0, timeouts)
        assertEquals(0, pings.size)
        assertEquals("artifact", keepAlive.activeArtifact)
    }
}
