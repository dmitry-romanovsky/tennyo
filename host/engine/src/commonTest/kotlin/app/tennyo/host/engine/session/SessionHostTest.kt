package app.tennyo.host.engine.session

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkPush
import app.tennyo.contract.SessionTimeouts
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionHostTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val clock = AtomicLong(hostWallClockMillis())
    private var platform = FakeHostPlatform()
    private var host = newHost()
    private val device = TestDevice()

    @AfterTest
    fun closeHost() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `pushes are numbered in order and reach the open channel`() {
        val ticket = approvedSession()
        val channel = TestChannel(host, ticket, device, clock::load).open()

        host.push(ticket.sessionId, LinkPush.GitInvalidated(revision = 1))
        host.push(ticket.sessionId, LinkPush.GitInvalidated(revision = 2))

        val seqs = channel.pushes().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(listOf(1L, 2L), channel.pushes().map { (it.push as LinkPush.GitInvalidated).revision })
    }

    @Test
    fun `a failed send closes the channel`() {
        val ticket = approvedSession()
        val channel = TestChannel(host, ticket, device, clock::load).open()
        assertTrue(host.isClientConnected(ticket.sessionId))

        channel.socket.failSends = true
        host.push(ticket.sessionId, LinkPush.GitInvalidated(revision = 1))

        assertFalse(channel.channel.isOpen)
        assertEquals(LinkChannel.INTERNAL_ERROR, channel.socket.closedWith?.first)
    }

    @Test
    fun `a closed channel detaches from its session`() {
        val ticket = approvedSession()
        val channel = TestChannel(host, ticket, device, clock::load).open()

        channel.channel.closed()
        clock.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS + 1)

        assertFalse(host.isClientConnected(ticket.sessionId))
    }

    @Test
    fun `the expiry monitor stops a session nobody approved`() {
        val ticket = host.startSession()

        clock.addAndFetch(SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS)
        scope.advanceTimeBy(IDLE_MONITOR_PERIOD)
        scope.runCurrent()

        assertNull(host.session(ticket.sessionId))
        assertEquals(0, host.sessionCount)
        assertEquals(1, platform.listenerReleases)
    }

    @Test
    fun `ordered call queue overflow closes the channel as busy`() {
        platform = FakeHostPlatform(deferBackground = true)
        host.close()
        host = newHost()
        val ticket = approvedSession()
        val channel = TestChannel(host, ticket, device, clock::load).open()

        repeat(MAX_PENDING_ORDERED_CALLS) { index ->
            channel.call(LinkCall.ShowPane(if (index % 2 == 0) ActivePanels.FILES else ActivePanels.LAUNCH))
            assertNull(channel.socket.closedWith, "call ${index + 1} was refused")
        }
        channel.call(LinkCall.OpenTerminalWindow)

        assertEquals(LinkFaultCode.BUSY, channel.socket.frames.filterIsInstance<LinkFrame.Fault>().single().fault)
        assertEquals(LinkChannel.POLICY_VIOLATION, channel.socket.closedWith?.first)
    }

    private fun newHost() = SessionHost(testPanes(), platform, scope, clock::load)

    private fun approvedSession(): SessionTicket =
        host.startSession().also { ticket ->
            assertNotNull(host.session(ticket.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
        }

    private companion object {
        const val MAX_PENDING_ORDERED_CALLS = 256
        val IDLE_MONITOR_PERIOD = 5.seconds
    }
}
