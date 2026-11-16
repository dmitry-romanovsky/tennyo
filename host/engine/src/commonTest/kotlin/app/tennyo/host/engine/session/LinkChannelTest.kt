package app.tennyo.host.engine.session

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.contract.SessionTimeouts
import app.tennyo.contract.WriteFileRequest
import app.tennyo.host.engine.panes.FakeProjectFiles
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The channel end to end, with real proofs, call authentication and the host's own handlers: reconnect and rejection
 * cases alongside one working call per pane.
 */
class LinkChannelTest {
    private val clock = AtomicLong(hostWallClockMillis())
    private val scope = CoroutineScope(SupervisorJob())
    private val files = FakeProjectFiles()
        .file("/work/project/src/Main.kt", "fun main() {}")
        .file("/work/secret.txt", "outside the project")
        .link("/work/project/escape", "/work/secret.txt")
    private val host = SessionHost(testPanes(files), FakeHostPlatform(), scope, clock::load)
    private val device = TestDevice()

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `the approved device opens a channel and a fresh client is told to load a snapshot`() {
        val channel = connect(approvedSession())

        val opened = assertNotNull(channel.opened)
        assertTrue(opened.snapshotRequired)
        assertNull(channel.socket.closedWith)
    }

    @Test
    fun `replay - a call whose sequence does not move forward closes the channel`() = runTest {
        val channel = connect(approvedSession())
        val first = channel.call(LinkCall.Stats)
        assertIs<LinkFrame.Reply>(channel.awaitAnswer(first))

        channel.receive(first.copy(id = 2L))

        assertFault(LinkFaultCode.REPLAYED, channel, callId = 2L)
    }

    @Test
    fun `replay - a recorded open proof cannot open another channel`() {
        val ticket = approvedSession()
        val recorded = TestChannel(host, ticket, device, clock::load)
        val proof = recorded.openProof(lastPushSeq = null)
        recorded.open(proof = proof)

        val replayed = TestChannel(host, ticket, device, clock::load).open(proof = proof)

        assertFault(LinkFaultCode.REPLAYED, replayed)
    }

    @Test
    fun `replay - an open proof bound to one resume point does not open at another`() {
        val ticket = approvedSession()
        val channel = TestChannel(host, ticket, device, clock::load)

        channel.open(lastPushSeq = 5, proof = channel.openProof(lastPushSeq = null))

        assertFault(LinkFaultCode.UNAUTHORIZED, channel)
    }

    @Test
    fun `a call changed after it was MACed closes the channel`() {
        val channel = connect(approvedSession())
        val signed = channel.signed(LinkCall.TerminalInput("terminal-1", "ls"))

        channel.receive(signed.copy(call = LinkCall.TerminalInput("terminal-1", "rm -rf ~")))

        assertFault(LinkFaultCode.MAC_INVALID, channel, callId = signed.id)
    }

    @Test
    fun `wrong device - another device cannot open the session`() {
        val ticket = approvedSession()

        val stranger = TestChannel(host, ticket, TestDevice(), clock::load).open()

        assertFault(LinkFaultCode.WRONG_DEVICE, stranger)
        assertNull(stranger.opened)
    }

    @Test
    fun `wrong device - nobody opens a session no device was approved for`() {
        val ticket = host.startSession()

        assertFault(LinkFaultCode.WRONG_DEVICE, TestChannel(host, ticket, device, clock::load).open())
    }

    @Test
    fun `wrong token - a guessed session token finds no session`() {
        val ticket = approvedSession()

        val guessed = TestChannel(host, ticket.copy(token = "guessed"), device, clock::load).open()

        assertFault(LinkFaultCode.SESSION_NOT_FOUND, guessed)
    }

    @Test
    fun `expired session - opening after the idle deadline ends the session`() {
        val ticket = approvedSession()

        clock.addAndFetch(SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS + 1)
        val late = TestChannel(host, ticket, device, clock::load).open()

        assertFault(LinkFaultCode.SESSION_NOT_FOUND, late)
        assertNull(host.session(ticket.sessionId))
    }

    @Test
    fun `expired session - a call after the absolute deadline ends the session and is never launch`() {
        val ticket = approvedSession()
        val channel = connect(ticket)

        clock.addAndFetch(SessionTimeouts.ABSOLUTE_SESSION_TIMEOUT_MILLIS)
        val call = channel.call(LinkCall.WriteFile(WriteFileRequest("src/Main.kt", "changed")))

        assertNull(host.session(ticket.sessionId))
        assertTrue(channel.pushes().any { it.push is LinkPush.SessionEnded })
        assertNull(channel.replyTo(call))
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `stopped session - the open channel is told and the session cannot be opened again`() {
        val ticket = approvedSession()
        val channel = connect(ticket)

        host.stopSession(ticket.sessionId, "Stopped in the IDE.")

        val ended = channel.pushes().map { it.push }.filterIsInstance<LinkPush.SessionEnded>().single()
        assertEquals("Stopped in the IDE.", ended.reason)
        assertFault(LinkFaultCode.SESSION_NOT_FOUND, TestChannel(host, ticket, device, clock::load).open())
    }

    @Test
    fun `stopped session - the app can stop its own session`() = runTest {
        val ticket = approvedSession()
        val channel = connect(ticket)

        val reply = channel.awaitAnswer(channel.call(LinkCall.StopSession))

        assertEquals(LinkResult.Done(accepted = true), (reply as LinkFrame.Reply).result)
        assertNull(host.session(ticket.sessionId))
    }

    @Test
    fun `reconnect inside the replay window resumes with exactly the missed pushes`() {
        val ticket = approvedSession()
        val first = connect(ticket)
        val session = assertNotNull(host.session(ticket.sessionId))
        repeat(3) { session.push(LinkPush.GitInvalidated(revision = it.toLong())) }
        val seqs = first.pushes().filter { it.push is LinkPush.GitInvalidated }.map { it.seq }

        val second = connect(ticket, lastPushSeq = seqs.first())

        assertFalse(assertNotNull(second.opened).snapshotRequired)
        val replayed = second.pushes().filter { it.push is LinkPush.GitInvalidated && it.seq <= seqs.last() }
        assertEquals(seqs.drop(1), replayed.map { it.seq })
        assertEquals(LinkChannel.NORMAL_CLOSURE, first.socket.closedWith?.first)
    }

    @Test
    fun `reconnect outside the replay window requires a snapshot instead of a partial replay`() {
        val ticket = approvedSession()
        val first = connect(ticket)
        val session = assertNotNull(host.session(ticket.sessionId))
        session.push(LinkPush.GitInvalidated(revision = 0))
        val lost = first.pushes().last().seq
        repeat(REPLAY_OVERFLOW) { session.push(LinkPush.GitInvalidated(revision = it.toLong() + 1)) }
        val latest = first.pushes().last().seq

        val second = connect(ticket, lastPushSeq = lost)

        assertTrue(assertNotNull(second.opened).snapshotRequired)
        assertTrue(second.pushes().none { it.seq <= latest })
    }

    @Test
    fun `malicious path - reads and writes outside the project are refused`() = runTest {
        val channel = connect(approvedSession())

        val traversal = channel.awaitAnswer(channel.call(LinkCall.ReadFile("../secret.txt")))
        val absolute = channel.awaitAnswer(channel.call(LinkCall.ReadFile("/work/secret.txt")))
        val link = channel.awaitAnswer(channel.call(LinkCall.ReadFile("escape")))
        val write = channel.awaitAnswer(channel.call(LinkCall.WriteFile(WriteFileRequest("../secret.txt", "owned"))))

        listOf(traversal, absolute, link, write).forEach { answer ->
            assertEquals(LinkFaultCode.TARGET_MISSING, (answer as LinkFrame.Fault).fault)
        }
        assertTrue(files.writes.isEmpty())
        assertNull(channel.socket.closedWith)
    }

    @Test
    fun `malicious input - a file write over the editing limit is refused`() = runTest {
        val channel = connect(approvedSession())
        val oversized = "x".repeat(LinkLimits.MAX_FILE_TEXT_BYTES + 1)

        val answer = channel.awaitAnswer(channel.call(LinkCall.WriteFile(WriteFileRequest("src/Main.kt", oversized))))

        assertEquals(LinkFaultCode.FILE_TOO_LARGE, (answer as LinkFrame.Fault).fault)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `malicious input - an oversized frame closes the channel`() {
        val channel = connect(approvedSession())

        channel.channel.receive("x".repeat(LinkLimits.MAX_FRAME_CHARS * 2))

        assertFault(LinkFaultCode.FRAME_TOO_LARGE, channel)
    }

    @Test
    fun `malicious input - an unknown op closes the channel`() {
        val channel = connect(approvedSession())

        channel.channel.receive("""{"op":"call","id":1,"seq":1,"mac":"m","call":{"op":"formatDisk"}}""")

        assertFault(LinkFaultCode.INVALID_CALL, channel)
    }

    @Test
    fun `malicious input - a call before the channel is open closes it`() {
        val ticket = approvedSession()
        val opened = connect(ticket)
        val unopened = TestChannel(host, ticket, device, clock::load)

        unopened.receive(opened.signed(LinkCall.Stats))

        assertFault(LinkFaultCode.INVALID_CALL, unopened)
    }

    @Test
    fun `malicious input - parts before the channel is open close it before anything is buffered`() {
        val unopened = TestChannel(host, approvedSession(), device, clock::load)

        unopened.channel.receive("""{"op":"part","partId":1,"index":0,"last":false,"text":"x"}""")

        assertFault(LinkFaultCode.INVALID_CALL, unopened)
    }

    @Test
    fun `malicious input - a socket that never opens is closed at its deadline`() {
        val unopened = TestChannel(host, approvedSession(), device, clock::load)

        unopened.channel.expireUnopened()

        assertFault(LinkFaultCode.INVALID_CALL, unopened)
    }

    @Test
    fun `an opened channel outlives its open deadline`() {
        val channel = connect(approvedSession())

        channel.channel.expireUnopened()

        assertNull(channel.socket.closedWith)
    }

    @Test
    fun `a reply longer than one frame arrives as ordered parts`() = runTest {
        val text = "x".repeat(LinkLimits.MAX_FRAME_CHARS + 1)
        files.file("/work/project/big.txt", text)
        val channel = connect(approvedSession())

        val answer = channel.awaitAnswer(channel.call(LinkCall.ReadFile("big.txt")))

        assertEquals(text, ((answer as LinkFrame.Reply).result as LinkResult.File).content.text)
        assertTrue(channel.socket.sent.size > channel.socket.frames.size)
    }

    @Test
    fun `session and files calls answer with typed results`() = runTest {
        val channel = connect(approvedSession())

        val stats = channel.awaitAnswer(channel.call(LinkCall.Stats))
        val file = channel.awaitAnswer(channel.call(LinkCall.ReadFile("src/Main.kt")))
        val tree = channel.awaitAnswer(channel.call(LinkCall.FileTree()))
        val written = channel.awaitAnswer(channel.call(LinkCall.WriteFile(WriteFileRequest("src/Main.kt", "fun main() = Unit"))))

        assertIs<LinkResult.Stats>((stats as LinkFrame.Reply).result)
        assertEquals("fun main() {}", ((file as LinkFrame.Reply).result as LinkResult.File).content.text)
        assertEquals("src", ((tree as LinkFrame.Reply).result as LinkResult.FileTree).tree.root.children.single().name)
        assertEquals("fun main() = Unit", ((written as LinkFrame.Reply).result as LinkResult.File).content.text)
    }

    @Test
    fun `launch, terminal and git calls answer with typed results or typed faults`() = runTest {
        val channel = connect(approvedSession())

        val configurations = channel.awaitAnswer(channel.call(LinkCall.LaunchConfigurations))
        val terminals = channel.awaitAnswer(channel.call(LinkCall.TerminalTabs))
        val terminal = channel.awaitAnswer(channel.call(LinkCall.CreateTerminal))
        val git = channel.awaitAnswer(channel.call(LinkCall.GitStatus()))

        assertIs<LinkResult.LaunchConfigurations>((configurations as LinkFrame.Reply).result)
        assertIs<LinkResult.TerminalTabs>((terminals as LinkFrame.Reply).result)
        assertEquals(LinkFaultCode.UNSUPPORTED, (terminal as LinkFrame.Fault).fault)
        assertEquals(LinkFaultCode.GIT_TARGET_STALE, (git as LinkFrame.Fault).fault)
    }

    @Test
    fun `showing a pane runs in order and pushes that pane's state`() = runTest {
        val channel = connect(approvedSession())

        val shown = channel.awaitAnswer(channel.call(LinkCall.ShowPane(ActivePanels.FILES)))

        assertEquals(LinkResult.Done(), (shown as LinkFrame.Reply).result)
        assertTrue(channel.pushes().any { it.push is LinkPush.EditorTabs })
    }

    private fun approvedSession(): SessionTicket =
        host.startSession().also { ticket ->
            assertNotNull(host.session(ticket.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
        }

    private fun connect(ticket: SessionTicket, lastPushSeq: Long? = null): TestChannel =
        TestChannel(host, ticket, device, clock::load).open(lastPushSeq)

    private fun assertFault(expected: LinkFaultCode, channel: TestChannel, callId: Long? = null) {
        val fault = channel.socket.frames.filterIsInstance<LinkFrame.Fault>().lastOrNull()
        assertEquals(expected, fault?.fault, "frames: ${channel.socket.sent}")
        assertEquals(callId, fault?.id)
        assertEquals(LinkChannel.POLICY_VIOLATION, channel.socket.closedWith?.first)
    }

    /** Calls that do not launch in order are answered from a worker; this waits in real time for the answer. */
    private suspend fun TestChannel.awaitAnswer(call: LinkFrame.Call): LinkFrame =
        withContext(Dispatchers.Default) {
            withTimeout(ANSWER_TIMEOUT) {
                var answer = replyTo(call)
                while (answer == null) {
                    delay(POLL_MILLIS)
                    answer = replyTo(call)
                }
                answer
            }
        }

    private companion object {
        const val REPLAY_OVERFLOW = 201
        const val POLL_MILLIS = 5L
        val ANSWER_TIMEOUT = 10.seconds
    }
}
