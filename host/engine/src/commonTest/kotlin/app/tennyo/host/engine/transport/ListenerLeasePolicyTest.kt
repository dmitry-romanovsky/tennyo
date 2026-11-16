package app.tennyo.host.engine.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ListenerLeasePolicyTest {
    @Test
    fun `discovery withdrawal retains listener while a session owns a lease`() {
        val policy = ListenerLeasePolicy()
        val discoveryLease = policy.acquire("discovery")
        val sessionLease = policy.acquire("session")

        assertTrue(policy.release(discoveryLease))
        assertTrue(policy.isListenerRequired)
        assertEquals(1, policy.count)

        assertTrue(policy.release(sessionLease))
        assertFalse(policy.isListenerRequired)
    }

    @Test
    fun `duplicate release cannot remove another owner lease`() {
        val policy = ListenerLeasePolicy()
        val firstSession = policy.acquire("session")
        val secondSession = policy.acquire("session")

        assertTrue(policy.release(firstSession))
        assertFalse(policy.release(firstSession))
        assertTrue(policy.isListenerRequired)
        assertEquals(1, policy.count)

        assertTrue(policy.release(secondSession))
        assertFalse(policy.isListenerRequired)
    }
}
