package app.tennyo.host.engine.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class AdmissionPolicyTest {
    @Test
    fun `liveness probe remains admitted after regular source budget is exhausted`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"

        repeat(AdmissionPolicy.MAX_PUBLIC_REQUESTS_PER_MINUTE) {
            assertTrue(policy.admitRequest(source, isLivenessProbe = false, nowMillis = 1_000L))
        }
        assertFalse(policy.admitRequest(source, isLivenessProbe = false, nowMillis = 1_000L))

        assertTrue(policy.admitRequest(source, isLivenessProbe = true, nowMillis = 1_000L))
    }

    @Test
    fun `liveness probe remains bounded per source`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"

        repeat(AdmissionPolicy.MAX_LIVENESS_REQUESTS_PER_MINUTE) {
            assertTrue(policy.admitRequest(source, isLivenessProbe = true, nowMillis = 1_000L))
        }

        assertFalse(policy.admitRequest(source, isLivenessProbe = true, nowMillis = 1_000L))
    }

    @Test
    fun `ordinary channel budget reserves capacity for event streams`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"

        repeat(AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE) {
            assertNotNull(policy.openOrdinaryHttp(source))
        }

        assertNull(policy.openOrdinaryHttp(source))
        val eventPermit = policy.openLinkSocket(source)
        assertNotNull(eventPermit)
        assertNotNull(policy.openLinkSocket(source))
        assertNull(policy.openLinkSocket(source))
        assertEquals(16, policy.snapshot().openChannels)
        assertEquals(2, policy.snapshot().linkSockets)
    }

    @Test
    fun `releasing an event permit does not release an ordinary permit`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"
        val ordinary = requireNotNull(policy.openOrdinaryHttp(source))
        val event = requireNotNull(policy.openLinkSocket(source))

        event.close()
        event.close()

        assertEquals(1, policy.snapshot().openChannels)
        assertEquals(1, policy.snapshot().ordinaryChannels)
        assertEquals(0, policy.snapshot().linkSockets)
        ordinary.close()
        assertEquals(0, policy.snapshot().openChannels)
    }

    @Test
    fun `global ordinary budget leaves event capacity for another source`() {
        val policy = AdmissionPolicy()

        repeat(
            AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS /
                    AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE
        ) { sourceIndex ->
            val source = "192.168.1.${sourceIndex + 1}"
            repeat(AdmissionPolicy.MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE) {
                assertNotNull(policy.openOrdinaryHttp(source))
            }
        }

        assertNull(policy.openOrdinaryHttp("192.168.1.100"))
        assertNotNull(policy.openLinkSocket("192.168.1.100"))
    }

    @Test
    fun `pending tls permits release exactly once on every terminal path`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"

        val completed = requireNotNull(policy.beginTlsHandshake(source))
        policy.finishTlsHandshake(completed)
        policy.finishTlsHandshake(completed)
        assertEquals(1, policy.snapshot().provisionalTlsChannels)
        policy.abortTlsHandshake(completed)

        val failed = requireNotNull(policy.beginTlsHandshake(source))
        policy.abortTlsHandshake(failed)
        policy.abortTlsHandshake(failed)

        assertEquals(0, policy.snapshot().pendingTlsHandshakes)
        repeat(AdmissionPolicy.MAX_PENDING_TLS_HANDSHAKES_PER_SOURCE) {
            assertNotNull(policy.beginTlsHandshake(source))
        }
        assertNull(policy.beginTlsHandshake(source))
    }

    @Test
    fun `successful tls remains provisionally bounded until first request classification`() {
        val policy = AdmissionPolicy()
        val source = "192.168.1.20"

        repeat(AdmissionPolicy.MAX_CHANNELS_PER_SOURCE) {
            policy.finishTlsHandshake(requireNotNull(policy.beginTlsHandshake(source)))
        }

        assertNull(policy.beginTlsHandshake(source))
        val provisional = policy.beginTlsHandshake("192.168.1.21")
        assertNotNull(provisional)
        policy.finishTlsHandshake(requireNotNull(provisional))
        assertNotNull(policy.promoteToOrdinaryHttp(provisional))
        assertEquals(AdmissionPolicy.MAX_CHANNELS_PER_SOURCE, policy.snapshot().provisionalTlsChannels)
    }
}
