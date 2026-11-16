package app.tennyo.host.engine.session

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.SessionEndReason
import app.tennyo.contract.SessionTimeouts
import app.tennyo.host.engine.auth.deviceKeyHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The proof gate in front of pairing and every channel: replay, wrong device, bad signature, stale protocol, and the
 * idle/pending expiry that ends a session nobody is using.
 */
class SessionAuthControllerTest {
    private val now = AtomicLong(PROOF_TIME)
    private val scope = CoroutineScope(SupervisorJob())
    private val host = SessionHost(testPanes(), FakeHostPlatform(), scope)
    private val sessions = mutableListOf<HostSession>()

    @AfterTest
    fun closeSessions() {
        sessions.forEach(HostSession::close)
        host.close()
        scope.cancel()
    }

    @Test
    fun `approved device with a valid proof is accepted`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        assertNull(session.auth.verifyApprovedProof(CHANNEL, "", device.proof(session, CHANNEL, signedAt = now.load())))
    }

    @Test
    fun `replayed proof is rejected`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")
        val proof = device.proof(session, CHANNEL, signedAt = now.load())

        assertNull(session.auth.verifyApprovedProof(CHANNEL, "", proof))
        assertEquals(LinkFaultCode.REPLAYED, session.auth.verifyApprovedProof(CHANNEL, "", proof))
    }

    @Test
    fun `a second device cannot open the session approved for the first`() {
        val session = session()
        session.auth.approveDevice(TestDevice().publicKey, "Pixel", "request-1")

        assertEquals(
            LinkFaultCode.WRONG_DEVICE,
            session.auth.verifyApprovedProof(CHANNEL, "", TestDevice().proof(session, CHANNEL, signedAt = now.load())),
        )
    }

    @Test
    fun `fingerprint that does not hash the presented key is rejected`() {
        val session = session()
        val device = TestDevice()

        val forged = device.proof(session, PAIR, signedAt = now.load()).copy(deviceFingerprint = deviceKeyHash("other-key"))

        assertEquals(LinkFaultCode.UNAUTHORIZED, session.auth.verifyClaimProof("", forged, session.artifact.token))
    }

    @Test
    fun `proof over a different endpoint or request is rejected`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        assertEquals(
            LinkFaultCode.UNAUTHORIZED,
            session.auth.verifyApprovedProof(CHANNEL, "", device.proof(session, PAIR, signedAt = now.load())),
        )
        assertEquals(
            LinkFaultCode.UNAUTHORIZED,
            session.auth.verifyApprovedProof(CHANNEL, "41", device.proof(session, CHANNEL, "40", signedAt = now.load())),
        )
    }

    @Test
    fun `a rejected signature does not burn the nonce`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")
        val proof = device.proof(session, CHANNEL, signedAt = now.load())

        assertEquals(LinkFaultCode.UNAUTHORIZED, session.auth.verifyApprovedProof(PAIR, "", proof))
        assertNull(session.auth.verifyApprovedProof(CHANNEL, "", proof))
    }

    @Test
    fun `older security version reads as a version gap, not a bad signature`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        val stale = device.proof(session, CHANNEL, signedAt = now.load())
            .copy(securityVersion = ProtocolVersions.SECURITY_VERSION - 1)

        assertEquals(LinkFaultCode.PROTOCOL_VERSION_UNSUPPORTED, session.auth.verifyApprovedProof(CHANNEL, "", stale))
    }

    @Test
    fun `a proof signed too long ago is refused`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")
        val proof = device.proof(session, CHANNEL, signedAt = now.load())

        now.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS * 2)

        assertEquals(LinkFaultCode.REPLAYED, session.auth.verifyApprovedProof(CHANNEL, "", proof))
    }

    @Test
    fun `missing device credentials are rejected`() {
        val session = session()
        val proof = TestDevice().proof(session, PAIR, signedAt = now.load())

        assertEquals(LinkFaultCode.UNAUTHORIZED, session.auth.verifyClaimProof("", proof.copy(signature = ""), session.artifact.token))
        assertEquals(LinkFaultCode.UNAUTHORIZED, session.auth.verifyClaimProof("", proof.copy(nonce = ""), session.artifact.token))
    }

    @Test
    fun `an accepted connection request is required before an unapproved device may confirm`() {
        val session = session()
        val device = TestDevice()

        assertEquals(
            LinkFaultCode.WRONG_DEVICE,
            session.auth.verifyPendingProof("", device.proof(session, PAIR, signedAt = now.load()), session.artifact.token),
        )
        assertNull(session.auth.confirmDevice(device.publicKey))
    }

    @Test
    fun `approved device expires after the idle timeout`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        now.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS)
        assertNull(session.expirationReason())

        now.addAndFetch(1)
        assertEquals(SessionEndReason.IdleExpired, session.expirationReason())
    }

    @Test
    fun `verified traffic keeps the approved device alive`() {
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "request-1")

        now.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS)
        session.auth.markApprovedDeviceSeen()
        now.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS)

        assertNull(session.expirationReason())
    }

    @Test
    fun `session nobody approves expires as pending`() {
        val session = session()

        now.addAndFetch(SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS)

        assertEquals(SessionEndReason.PendingExpired, session.expirationReason())
    }

    @Test
    fun `wall clock rollback cannot extend a deadline`() {
        val session = session()
        now.addAndFetch(SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS)
        assertEquals(SessionEndReason.PendingExpired, session.expirationReason())

        now.store(PROOF_TIME)

        assertEquals(SessionEndReason.PendingExpired, session.expirationReason())
    }

    private fun session(): HostSession =
        HostSession(
            artifact = testArtifact("auth-test-${sessions.size}", setOf(Capability.FILES)),
            owner = host,
            parentScope = scope,
            wallClockMillis = now::load,
        ).also(sessions::add)

    private companion object {
        const val PROOF_TIME = 1_700_000_000_000L
        const val CHANNEL = LinkEndpoints.CHANNEL
        const val PAIR = LinkEndpoints.PAIR
    }
}
