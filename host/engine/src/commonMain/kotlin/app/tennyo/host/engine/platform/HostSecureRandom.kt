package app.tennyo.host.engine.platform

import app.tennyo.crypto.secureRandomBytes
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A [Random] drawing every bit from the platform CSPRNG, for shuffles whose order is a secret. */
object HostSecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        val bytes = secureRandomBytes(Int.SIZE_BYTES)
        val value = bytes.fold(0) { word, byte -> (word shl Byte.SIZE_BITS) or (byte.toInt() and BYTE_MASK) }
        return value ushr (Int.SIZE_BITS - bitCount)
    }

    private const val BYTE_MASK = 0xFF
}

/** A random (version 4, RFC 4122 variant) UUID in the usual lower-case 8-4-4-4-12 form. */
@OptIn(ExperimentalUuidApi::class)
fun hostRandomUuid(): String = Uuid.random().toString()
