package app.tennyo.host.engine.session

import app.tennyo.contract.SessionEndReason
import app.tennyo.contract.SessionTimeouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionTimeoutsTest {
    @Test
    fun `approved device remains valid for three minutes without traffic`() {
        assertEquals(3 * 60 * 1_000L, SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS)
    }

    @Test
    fun `session expiry sweep runs every thirty seconds`() {
        assertEquals(30L, SessionHost.ABSOLUTE_EXPIRY_CHECK_SECONDS)
    }

    @Test
    fun `absolute expiry is visible to call admission immediately`() {
        val now = AtomicLong(1_000L)
        val scope = CoroutineScope(SupervisorJob())
        val host = SessionHost(testPanes(), FakeHostPlatform(), scope)
        val session = HostSession(testArtifact("timeout-test"), host, scope, wallClockMillis = now::load)
        try {
            assertNull(session.expirationReason())

            now.addAndFetch(SessionTimeouts.ABSOLUTE_SESSION_TIMEOUT_MILLIS)

            assertEquals(
                SessionEndReason.AbsoluteExpired,
                session.expirationReason(),
            )
        } finally {
            session.close()
            host.close()
            scope.cancel()
        }
    }
}
