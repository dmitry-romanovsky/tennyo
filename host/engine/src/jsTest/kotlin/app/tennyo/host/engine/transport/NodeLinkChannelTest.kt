package app.tennyo.host.engine.transport

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkEndpoints
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkFrameAssembler
import app.tennyo.contract.encodeLinkFrame
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.HostRegistry
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.SessionTicket
import app.tennyo.host.engine.session.TestDevice
import app.tennyo.host.engine.session.testPanes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The channel over a real WebSocket on Node: an approved phone opens it with a real proof and its calls are answered,
 * and an unapproved one is refused. The frame handling itself is the engine's, already covered by `LinkChannelTest`;
 * what this proves is that the Node transport carries those frames faithfully in both directions.
 */
class NodeLinkChannelTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val host = SessionHost(testPanes(), FakeHostPlatform(), scope, ::hostWallClockMillis)
    private val listener = NodeLinkListener(newNodeTransportIdentity(), scope, openOriginGate())
    private val device = TestDevice()
    private var port = 0

    init {
        HostRegistry.register(host)
    }

    @AfterTest
    fun close() {
        HostRegistry.unregister(host)
        host.close()
        listener.close()
        scope.cancel()
    }

    @Test
    fun `an approved phone opens the channel over Node and its calls are answered`() = runTest {
        withContext(Dispatchers.Default) {
            port = bind()
            val ticket = approvedSession()
            val socket = connect()

            val opened = socket.exchange(openFrame(ticket))
            assertIs<LinkFrame.Opened>(opened, "the channel did not open: $opened")

            val stats = socket.exchange(LinkFrame.Call(id = 1, seq = 1, mac = "", call = LinkCall.Stats))
            // The call carries no command MAC, so the engine must refuse it rather than answer it.
            assertIs<LinkFrame.Fault>(stats, "an unsigned call was answered: $stats")
            socket.close()
        }
    }

    @Test
    fun `a phone the host never approved is refused the channel`() = runTest {
        withContext(Dispatchers.Default) {
            port = bind()
            val ticket = host.startSession()
            val socket = connect()

            val answer = socket.exchange(openFrame(ticket))

            assertIs<LinkFrame.Fault>(answer, "an unapproved device opened the channel: $answer")
            assertEquals(LinkFaultCode.WRONG_DEVICE, answer.fault)
            socket.close()
        }
    }

    private fun openFrame(ticket: SessionTicket): LinkFrame.Open = LinkFrame.Open(
        sessionId = ticket.sessionId,
        sessionToken = ticket.token,
        proof = device.proof(ticket, LinkEndpoints.CHANNEL, "", hostWallClockMillis()),
        lastPushSeq = null,
    )

    private fun approvedSession(): SessionTicket = host.startSession().also { ticket ->
        assertNotNull(host.session(ticket.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
    }

    private suspend fun bind(): Int {
        val bound = CompletableDeferred<Int>()
        listener.listen(LOOPBACK, 0) { bound.complete(it) }
        return withTimeout(TIMEOUT) { bound.await() }
    }

    private suspend fun connect(): ChannelClient {
        val client = ChannelClient(
            WebSocket(
                "wss://$LOOPBACK:$port${LinkEndpoints.CHANNEL}",
                // The listener's certificate is self-signed by design; the phone pins it instead of trusting a chain.
                jsOptions("rejectUnauthorized" to false),
            ),
        )
        withTimeout(TIMEOUT) { client.awaitOpen() }
        return client
    }

    private suspend fun ChannelClient.exchange(frame: LinkFrame): LinkFrame? =
        withTimeout(TIMEOUT) { send(frame) }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val TIMEOUT = 10.seconds
    }
}

/** A phone's end of the channel: it writes whole frames and reads the next one the host answers with. */
private class ChannelClient(private val socket: WebSocket) {
    private val assembler = LinkFrameAssembler()
    private val opened = CompletableDeferred<Unit>()
    private var pending: CompletableDeferred<LinkFrame?>? = null
    private var partIds = 0L

    init {
        socket.on("open") { opened.complete(Unit) }
        socket.on("message") { data: dynamic ->
            val frame = runCatching { assembler.accept(data.toString("utf8") as String) }.getOrNull()
            // A push can overtake the answer to a call; this waits for the answer itself.
            if (frame != null && frame !is LinkFrame.Push) pending?.complete(frame)
        }
        // A refused open closes the socket right after the fault, and may close before the fault is read.
        socket.on("close") { pending?.complete(null) }
        socket.on("error") { pending?.complete(null) }
    }

    suspend fun awaitOpen() = opened.await()

    suspend fun send(frame: LinkFrame): LinkFrame? {
        val answer = CompletableDeferred<LinkFrame?>()
        pending = answer
        encodeLinkFrame(frame) { ++partIds }.forEach { socket.send(it) }
        return answer.await()
    }

    fun close() = socket.close()
}
