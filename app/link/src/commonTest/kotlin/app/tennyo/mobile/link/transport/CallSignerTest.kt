package app.tennyo.mobile.link.transport

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFrame
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.toBase64Url
import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class CallSignerTest {
    /** Same literals as the host's `call mac key and mac are stable across modules`. */
    @Test
    fun callMacKeyAndMacAreStableAcrossModules() {
        val vectorTicket = TICKET.copy(fingerprint = "fingerprint")
        val commandKey = SecurityDerivations.commandMacKey(
            commandKeySeed = "command-key-seed".encodeToByteArray(),
            sessionToken = "token",
            streamId = "stream-1",
            approvedDeviceFingerprint = "device-fingerprint",
        )

        assertEquals("0rl3RqT4Lf4naNtoy9eesKxYos4JklMo9y5TUraju6Q", commandKey.toBase64Url())
        assertEquals(
            "f7IbyEmQ8VCMV7I-HzxzSHWsS7EudsJ7kXR4PvbOjj4",
            signer(vectorTicket, commandKeySeed = "command-key-seed")
                .mac(seq = 7, id = 3, call = LinkCall.TerminalInput(terminalId = "terminal-1", text = "ls")),
        )
    }

    @Test
    fun signsCallsWithAMonotonicSequence() {
        val signer = signer()

        val first = signer.sign(id = 1, call = LinkCall.TerminalInput("terminal-1", "a"))
        val second = signer.sign(id = 2, call = LinkCall.TerminalInput("terminal-1", "b"))

        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertNotEquals(first.mac, second.mac)
    }

    @Test
    fun theMacCoversTheCallItsIdAndItsSequence() {
        val signer = signer()
        val signed = signer.sign(id = 1, call = LinkCall.TerminalInput("terminal-1", "a"))

        assertNotEquals(signed.mac, signer.mac(signed.seq, signed.id, LinkCall.TerminalInput("terminal-1", "b")))
        assertNotEquals(signed.mac, signer.mac(signed.seq, signed.id + 1, signed.call))
        assertNotEquals(signed.mac, signer.mac(signed.seq + 1, signed.id, signed.call))
    }

    @Test
    fun terminalInputSigningStaysWithinHotPathBudget() {
        val signer = signer()
        repeat(TERMINAL_SIGNING_WARMUP_ITERATIONS) { index ->
            signer.sign(id = index.toLong(), call = LinkCall.TerminalInput("terminal-1", "x"))
        }

        var signedMacChars = 0
        val startedAt = TimeSource.Monotonic.markNow()
        repeat(TERMINAL_SIGNING_ITERATIONS) { index ->
            signedMacChars += signer.sign(id = index.toLong(), call = LinkCall.TerminalInput("terminal-1", "x")).mac.length
        }
        val averageNanos = startedAt.elapsedNow().inWholeNanoseconds / TERMINAL_SIGNING_ITERATIONS

        assertTrue(signedMacChars > 0)
        assertTrue(
            averageNanos <= TERMINAL_SIGNING_AVERAGE_BUDGET_NANOS,
            "Signing a terminal input averaged ${averageNanos}ns, budget is ${TERMINAL_SIGNING_AVERAGE_BUDGET_NANOS}ns",
        )
    }

    private fun signer(ticket: LinkTicket = TICKET, commandKeySeed: String = "seed-value"): CallSigner =
        CallSigner(
            ticket = ticket,
            opened = LinkFrame.Opened(
                streamId = "stream-1",
                commandKeySeed = commandKeySeed.encodeToByteArray().toBase64Url(),
                serverTimeEpochMillis = 1_000L,
                snapshotRequired = false,
            ),
            approvedDeviceFingerprint = "device-fingerprint",
        )

    private companion object {
        const val TERMINAL_SIGNING_WARMUP_ITERATIONS = 512
        const val TERMINAL_SIGNING_ITERATIONS = 4_096
        const val TERMINAL_SIGNING_AVERAGE_BUDGET_NANOS = 750_000L

        val TICKET = LinkTicket(
            endpoint = "https://192.168.1.20:63342",
            sessionId = "session",
            token = "token",
            fingerprint = "host-fingerprint",
            projectName = "Project",
            projectPath = "/tmp/Project",
        )
    }
}
