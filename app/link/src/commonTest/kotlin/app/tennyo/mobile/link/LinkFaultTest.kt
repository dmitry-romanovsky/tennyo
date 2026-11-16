package app.tennyo.mobile.link

import app.tennyo.contract.ErrorCodes
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.wireName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinkFaultTest {
    @Test
    fun anExpiredOrUnknownSessionEndsTheSession() {
        assertTrue(LinkFault.fromHost(410, LinkFaultCode.SESSION_EXPIRED.wireName, "The session expired.").endsSession)
        assertTrue(LinkFault.fromHost(404, LinkFaultCode.SESSION_NOT_FOUND.wireName, "The session is gone.").endsSession)
    }

    @Test
    fun aProtocolMismatchEndsTheSessionInsteadOfRetrying() {
        assertTrue(LinkFault.ProtocolMismatch(ProtocolVersions.SECURITY_VERSION + 1).endsSession)
    }

    @Test
    fun aDeviceApprovalFailureIsAuthAndKeepsTheSession() {
        val fault = LinkFault.fromHost(401, LinkFaultCode.WRONG_DEVICE.wireName, "Device approval is required.")

        assertIs<LinkFault.Auth>(fault)
        assertTrue(fault.isDeviceNotApproved)
        assertFalse(fault.endsSession)
    }

    @Test
    fun hostErrorsAreClassifiedByCodeAndStatus() {
        assertIs<LinkFault.RateLimited>(LinkFault.fromHost(429, "rate_limited", "Slow down."))
        assertIs<LinkFault.TargetMissing>(LinkFault.fromHost(404, LinkFaultCode.TARGET_MISSING.wireName, "Tab closed."))
        assertIs<LinkFault.Auth>(LinkFault.fromHost(401, "http_401", "Unauthorized"))
        assertIs<LinkFault.Refused>(LinkFault.fromHost(409, ErrorCodes.CONNECTION_REQUEST_PENDING, "Pending."))
    }

    @Test
    fun aRateLimitIsFoundThroughWrappingFailures() {
        val wrapped = IllegalStateException("request failed", LinkFault.fromHost(429, "http_429", "Too many requests"))

        assertTrue(wrapped.isRateLimited)
    }

    @Test
    fun otherFailuresBecomeTransportFaultsAndFaultsPassThrough() {
        val fault = LinkFault.fromHost(500, "http_500", "Server error")

        assertSame(fault, fault.toLinkFault())
        assertIs<LinkFault.Transport>(IllegalStateException("socket closed").toLinkFault())
    }

    @Test
    fun cancellationIsRethrownButATimeoutIsAFault() {
        assertFailsWith<CancellationException> { CancellationException("left the screen").toLinkFault() }
        val timeout = assertFailsWith<TimeoutCancellationException> {
            runBlocking { withTimeout(1) { kotlinx.coroutines.delay(1_000) } }
        }
        val fault = assertIs<LinkFault.Transport>(timeout.toLinkFault())
        assertTrue(fault.isConnectTimeout)
    }
}
