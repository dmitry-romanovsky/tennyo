package app.tennyo.mobile.link.transport

import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkFrameAssembler
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.encodeLinkFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the app reads off the channel: the host's own frames, and long ones reassembled from their parts. */
class LinkFrameDecodingTest {
    @Test
    fun decodesTheOpenedFrameTheHostSends() {
        val frame: LinkFrame = LinkFrame.Opened(
            streamId = "stream",
            commandKeySeed = "seed",
            serverTimeEpochMillis = 1234L,
            snapshotRequired = true,
        )

        assertEquals(frame, decode(LinkJson.encodeToString(LinkFrame.serializer(), frame)))
    }

    @Test
    fun decodesATerminalOutputPush() {
        val payload = """{"op":"push","seq":7,"push":{"op":"terminalFrame","frame":{"terminalId":"t1","text":"updated"}}}"""

        assertEquals(
            LinkFrame.Push(seq = 7, push = LinkPush.TerminalOutput(TerminalFrame(terminalId = "t1", text = "updated"))),
            decode(payload),
        )
    }

    @Test
    fun reassemblesAReplyTooLongForOneFrame() {
        val text = "x".repeat(LinkLimits.MAX_FRAME_CHARS + 1)
        val frame: LinkFrame = LinkFrame.Reply(id = 1, result = LinkResult.Done(accepted = true))
        val long: LinkFrame = LinkFrame.Push(seq = 1, push = LinkPush.SessionEnded(reason = text))
        val assembler = LinkFrameAssembler()
        var partIds = 0L

        val single = encodeLinkFrame(frame) { ++partIds }
        val parts = encodeLinkFrame(long) { ++partIds }

        assertEquals(1, single.size)
        assertEquals(frame, assembler.accept(single.single()))
        assertEquals(3, parts.size)
        assertNull(assembler.accept(parts[0]))
        assertNull(assembler.accept(parts[1]))
        assertEquals(long, assembler.accept(parts[2]))
    }

    private fun decode(payload: String): LinkFrame? = LinkFrameAssembler().accept(payload)
}
