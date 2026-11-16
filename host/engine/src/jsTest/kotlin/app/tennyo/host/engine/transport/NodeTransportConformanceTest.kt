package app.tennyo.host.engine.transport

import app.tennyo.host.engine.platform.jsOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The parity gate for the Node listener: every [TransportConformanceCases] exchange, written raw to a fresh
 * connection, must be answered the way the VS Code listener answers it. A case that passes on one host and not the
 * other means the two IDEs have drifted apart on the wire, which is what this suite exists to catch.
 */
class NodeTransportConformanceTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val admissionPolicy = AdmissionPolicy()
    private val listener = NodeLinkListener(newNodeTransportIdentity(), scope, openOriginGate(), admissionPolicy)
    private var port = 0

    @AfterTest
    fun close() {
        listener.close()
        scope.cancel()
    }

    @Test
    fun `every transport conformance case is answered as the VS Code listener answers it`() = runTest {
        // Node's sockets and timers run on the real event loop, which the test scheduler's virtual clock never reaches.
        withContext(Dispatchers.Default) {
            port = bind()
            TransportConformanceCases.all.forEach { case ->
                val exchange = withTimeout(TIMEOUT) { exchange(case) }
                if (case.expectedStatus == null) {
                    assertTrue(exchange.isEmpty(), "${case.name}: expected no answer, got ${exchange.take(64)}")
                } else {
                    assertEquals(case.expectedStatus, exchange.status(), "${case.name}: ${exchange.take(96)}")
                    case.expectedErrorCode?.let { code ->
                        assertTrue("\"$code\"" in exchange, "${case.name}: expected $code in ${exchange.take(160)}")
                    }
                }
            }
            // Every connection the suite opened is gone, so none of them may still hold an admission permit. The
            // listener learns of a hang-up a tick after the client does, so this waits for that rather than racing it.
            withTimeout(TIMEOUT) {
                while (admissionPolicy.snapshot().openChannels > 0) delay(RELEASE_POLL)
            }
        }
    }

    private suspend fun bind(): Int {
        val bound = CompletableDeferred<Int>()
        listener.listen(LOOPBACK, 0) { bound.complete(it) }
        return withTimeout(TIMEOUT) { bound.await() }
    }

    /**
     * Writes [TransportConformanceCase.rawRequest] byte for byte and answers with everything read back. A case that
     * keeps the connection open answers as soon as the response is complete, so the client is the one to hang up.
     */
    private suspend fun exchange(case: TransportConformanceCase): String {
        val answer = CompletableDeferred<String>()
        var received = ""
        val options = jsOptions(
            "port" to port,
            "host" to LOOPBACK,
            // The listener's certificate is self-signed by design; the phone pins it instead of trusting a chain.
            "rejectUnauthorized" to false,
        )
        val closed = CompletableDeferred<Unit>()
        val socket = if (case.overTls) NodeTls.connect(options) {} else NodeNet.connect(options) {}
        socket.on("data") { chunk: dynamic ->
            received += chunk.toString("utf8") as String
            if (received.isCompleteResponse()) answer.complete(received)
        }
        socket.on("close") {
            answer.complete(received)
            closed.complete(Unit)
        }
        socket.on("error") { answer.complete(received) }
        socket.write(case.rawRequest)
        val exchanged = answer.await()
        // Hanging up and waiting for it lets the listener release this connection's admission before the next case.
        socket.destroy()
        closed.await()
        return exchanged
    }

    /** Whether the bytes read so far are a whole response: the head, plus the body its `content-length` promises. */
    private fun String.isCompleteResponse(): Boolean {
        val separator = indexOf(HEAD_SEPARATOR)
        if (separator < 0) return false
        val declared = substring(0, separator)
            .lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: return false
        return length - (separator + HEAD_SEPARATOR.length) >= declared
    }

    private fun String.status(): Int? =
        substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HEAD_SEPARATOR = "\r\n\r\n"
        val TIMEOUT = 10.seconds
        val RELEASE_POLL = 20.milliseconds
    }
}

@JsModule("node:tls")
@JsNonModule
private external object NodeTls {
    fun connect(options: dynamic, onConnected: () -> Unit): NodeSocket
}

@JsModule("node:net")
@JsNonModule
private external object NodeNet {
    fun connect(options: dynamic, onConnected: () -> Unit): NodeSocket
}
