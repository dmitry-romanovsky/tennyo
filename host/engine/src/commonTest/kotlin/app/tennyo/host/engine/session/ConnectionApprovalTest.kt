package app.tennyo.host.engine.session

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.RememberedPairResumeStatus
import app.tennyo.host.engine.auth.deviceKeyHash
import app.tennyo.host.engine.pairing.RememberedPairAttempt
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.engine.pairing.rememberedPairProof
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Who gets into a session: the user answers for a new device, while an already trusted device and a device
 * resuming a remembered pair are let in without asking, and everyone else is turned away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionApprovalTest {
    private val now = AtomicLong(NOW)
    private val scope = CoroutineScope(SupervisorJob())
    private val platform = FakeHostPlatform()
    private val host = SessionHost(testPanes(), platform, scope)
    private val sessions = mutableListOf<HostSession>()

    @AfterTest
    fun closeSessions() {
        sessions.forEach(HostSession::close)
        host.close()
        scope.cancel()
    }

    @Test
    fun `a device the user accepts is let in`() = runTest {
        val session = session()
        val device = TestDevice()

        val approval = async { session.auth.requestConnectionApproval(device) }
        runCurrent()
        val shown = platform.shownRequests.single().second
        assertEquals(ConnectionRequestStatus.PENDING, shown.status)
        assertTrue(session.auth.acceptConnectionRequest(shown.id))

        assertEquals(ConnectionApproval.ACCEPTED, approval.await())
    }

    @Test
    fun `the prompt shows a short single-line name whatever the phone claims`() = runTest {
        val session = session()

        backgroundScope.async {
            session.auth.requestConnectionApproval(
                clientId = "android",
                clientName = "Pixel\n‮Approved by admin" + "x".repeat(10_000),
                clientVersion = "1.0\r\n forged log line",
                devicePublicKey = TestDevice().publicKey,
                rememberedPairId = null,
            )
        }
        runCurrent()

        val shown = platform.shownRequests.single().second
        assertEquals("PixelApproved by admin" + "x".repeat(42), shown.clientName)
        assertEquals("1.0forged log line", shown.clientVersion)
    }

    @Test
    fun `a device the user rejects is turned away`() = runTest {
        val session = session()

        val approval = async { session.auth.requestConnectionApproval(TestDevice()) }
        runCurrent()
        assertTrue(session.auth.rejectConnectionRequest(platform.shownRequests.single().second.id))

        assertEquals(ConnectionApproval.REJECTED, approval.await())
    }

    @Test
    fun `a request nobody answers times out`() = runTest {
        val session = session()

        assertEquals(ConnectionApproval.TIMED_OUT, session.auth.requestConnectionApproval(TestDevice()))
    }

    @Test
    fun `a second device waits for no answer while one request is pending`() = runTest {
        val session = session()

        backgroundScope.async { session.auth.requestConnectionApproval(TestDevice(), clientId = "first") }
        runCurrent()

        assertEquals(
            ConnectionApproval.BUSY,
            session.auth.requestConnectionApproval(TestDevice(), clientId = "second"),
        )
    }

    @Test
    fun `a second device cannot ask once another one is approved`() = runTest {
        val session = session()
        session.auth.approveDevice(TestDevice().publicKey, "Pixel", "discovery-1")

        assertEquals(ConnectionApproval.REJECTED, session.auth.requestConnectionApproval(TestDevice()))
        assertTrue(platform.shownRequests.isEmpty())
    }

    @Test
    fun `the approved device reconnects without asking the user again`() = runTest {
        platform.discoveryEnabled = true
        val session = session()
        val device = TestDevice()
        session.auth.approveDevice(device.publicKey, "Pixel", "discovery-1")

        assertEquals(ConnectionApproval.ACCEPTED, session.auth.requestConnectionApproval(device))
        assertTrue(platform.shownRequests.isEmpty())
    }

    @Test
    fun `a device resuming a remembered pair is let in without asking the user`() = runTest {
        platform.discoveryEnabled = true
        val session = session()
        val device = TestDevice()
        val record = rememberedPairFor(device)

        val approval = session.auth.requestConnectionApproval(device, rememberedPairId = record.pairId)

        assertEquals(ConnectionApproval.ACCEPTED, approval)
        assertTrue(platform.shownRequests.isEmpty())
    }

    @Test
    fun `a remembered pair is not enough while discovery is off`() = runTest {
        val session = session()
        val device = TestDevice()
        val record = rememberedPairFor(device)

        val approval = async { session.auth.requestConnectionApproval(device, rememberedPairId = record.pairId) }
        runCurrent()
        val shown = platform.shownRequests.single().second
        assertEquals(ConnectionRequestStatus.PENDING, shown.status)
        session.auth.rejectConnectionRequest(shown.id)

        assertEquals(ConnectionApproval.REJECTED, approval.await())
    }

    @Test
    fun `a resume refused after its proof checks leaves the device unapproved`() = runTest {
        platform.discoveryEnabled = true
        // The resume challenge expires against the real clock, so this session starts on it.
        now.store(hostWallClockMillis())
        val session = session()
        val device = TestDevice()
        val record = rememberedPairFor(device)
        assertEquals(
            ConnectionApproval.ACCEPTED,
            session.auth.requestConnectionApproval(device, rememberedPairId = record.pairId),
        )
        val challenge = assertNotNull(session.auth.currentPairingChallenge())
        // The proof only MACs the client's ephemeral key, so one that does not decode passes every check up to the
        // key agreement, which then refuses it.
        val attempt = RememberedPairAttempt(
            artifact = session.artifact,
            route = LinkEndpoints.PAIR,
            challengeId = challenge.challengeId,
            attemptId = "attempt-1",
            serverChallenge = challenge.serverChallenge,
            deviceFingerprint = device.fingerprint,
            clientEphemeralPublicKey = "not an ec key",
            companionEphemeralPublicKey = challenge.companionEphemeralPublicKey,
        )

        val response = session.auth.resumeRememberedPair(
            devicePublicKey = device.publicKey,
            pairId = record.pairId,
            challengeId = attempt.challengeId,
            attemptId = attempt.attemptId,
            clientEphemeralPublicKey = attempt.clientEphemeralPublicKey,
            clientPairProof = rememberedPairProof(record.pairSecret, attempt),
        )

        assertEquals(RememberedPairResumeStatus.Rejected, response.status)
        assertFalse(session.auth.isDeviceApproved(device.publicKey))
    }

    /**
     * A pair the host recognizes as its own, the way a finished pairing leaves it. Its expiry is drawn from the
     * real clock, because the pair store checks it against the clock the store itself reads.
     */
    private fun rememberedPairFor(device: TestDevice): RememberedPairRecord {
        val record = newRememberedPair(
            deviceLabel = "Pixel",
            deviceKeyHash = deviceKeyHash(device.publicKey),
        ).second.copy(companionId = platform.companionId())
        host.saveRememberedPair(record)
        return record
    }

    private suspend fun SessionAuthController.requestConnectionApproval(
        device: TestDevice,
        clientId: String = "android",
        rememberedPairId: String? = null,
    ): ConnectionApproval =
        requestConnectionApproval(
            clientId = clientId,
            clientName = "Pixel",
            clientVersion = "1.0",
            devicePublicKey = device.publicKey,
            rememberedPairId = rememberedPairId,
        )

    private fun session(): HostSession =
        HostSession(
            artifact = testArtifact("approval-test-${sessions.size}", setOf(Capability.FILES)),
            owner = host,
            parentScope = scope,
            wallClockMillis = now::load,
        ).also(sessions::add)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
