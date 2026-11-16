package app.tennyo.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Expected strings come from `java.util.Base64.getUrlEncoder()`, the encoder the plugin used before the move. */
class EncodingTest {
    @Test
    fun encodesLikeTheJavaUrlEncoderWithoutPadding() {
        UNPADDED.forEachIndexed { length, expected ->
            assertEquals(expected, BYTES.copyOf(length).toBase64Url(), "length $length")
        }
    }

    @Test
    fun decodesBothThePaddedAndUnpaddedForms() {
        UNPADDED.forEachIndexed { length, unpadded ->
            assertContentEquals(BYTES.copyOf(length), unpadded.decodeBase64Url(), "unpadded length $length")
            assertContentEquals(BYTES.copyOf(length), PADDED[length].decodeBase64Url(), "padded length $length")
        }
    }

    @Test
    fun rejectsTheStandardAlphabet() {
        assertFailsWith<IllegalArgumentException> { "+_8A".decodeBase64Url() }
        assertFailsWith<IllegalArgumentException> { "-/8A".decodeBase64Url() }
    }

    private companion object {
        val BYTES = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x00, 0x3e, 0x3f, 0x80.toByte(), 0x7f)
        val UNPADDED = listOf("", "-w", "-_8", "-_8A", "-_8APg", "-_8APj8", "-_8APj-A", "-_8APj-Afw")
        val PADDED = listOf("", "-w==", "-_8=", "-_8A", "-_8APg==", "-_8APj8=", "-_8APj-A", "-_8APj-Afw==")
    }
}
