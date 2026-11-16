package app.tennyo.host.engine.pairing

import app.tennyo.contract.SessionTimeouts
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.RememberedPairIssuer
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.testPanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RememberedPairLifetimeTest {
    @Test
    fun `a new remembered pair expires after the contract TTL`() {
        val (material, record) = newRememberedPair("Pixel", "device-a", nowEpochMillis = NOW)

        assertEquals(SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS, material.expiresAtEpochMillis - NOW)
        assertEquals(SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS, record.expiresAtEpochMillis - NOW)
    }

    @Test
    fun `a rotated remembered pair slides to the contract TTL from the reconnect`() {
        val pairStore = InMemoryPairStore()
        val scope = CoroutineScope(SupervisorJob())
        val host = SessionHost(testPanes(), FakeHostPlatform(pairStore = pairStore), scope)
        try {
            val session = checkNotNull(host.session(host.startSession().sessionId))
            val (_, previous) = newRememberedPair("Pixel", "device-a")
            pairStore.save(previous)

            val before = hostWallClockMillis()
            RememberedPairIssuer(session).rotate(previous, "Pixel", ByteArray(32))
            val after = hostWallClockMillis()

            val rotated = pairStore.summaries().single()
            val ttl = SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS
            assertTrue(rotated.expiresAtEpochMillis in (before + ttl)..(after + ttl))
        } finally {
            host.close()
            scope.cancel()
        }
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
