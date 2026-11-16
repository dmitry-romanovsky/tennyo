package app.tennyo.host.engine.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A session behind an open pairing window is waiting to be found, so the host keeps advertising it. Without this a
 * phone never sees the IDE while visibility is "all": opening the window starts a session, and the LAN advertisement
 * the phone browses for must keep running while that session is active.
 */
class PairingWindowVisibilityTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val host = SessionHost(testPanes(), FakeHostPlatform(discoveryEnabled = true), scope)

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `a session behind an open pairing window is not claimed`() {
        assertFalse(host.hasClaimedSession)

        host.openPairingWindow()

        assertFalse(host.hasClaimedSession)
    }

    @Test
    fun `a session is claimed once its window closes`() {
        host.openPairingWindow()

        host.pairingWindow.close()

        assertTrue(host.hasClaimedSession)
    }

    @Test
    fun `leaving the window takes the session nobody claimed with it`() {
        host.openPairingWindow()

        host.closePairingWindow()

        assertEquals(0, host.sessionCount)
        assertFalse(host.hasClaimedSession)
    }
}
