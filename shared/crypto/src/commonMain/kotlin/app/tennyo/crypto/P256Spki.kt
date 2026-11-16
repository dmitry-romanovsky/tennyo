package app.tennyo.crypto

/**
 * Converts between the SPKI encoding the protocol exchanges and the raw X9.63 point (`0x04 || X || Y`) that APIs such
 * as Security.framework import and export. Only uncompressed P-256 points are accepted.
 */
object P256Spki {
    /** The DER header of a P-256 SubjectPublicKeyInfo: algorithm identifier, curve OID and the bit-string tag. */
    private val HEADER = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(),
        0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A,
        0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        0x03, 0x42, 0x00,
    )
    private const val RAW_POINT_BYTES = 65
    private const val UNCOMPRESSED_POINT: Byte = 0x04

    fun fromRawPoint(rawPoint: ByteArray): ByteArray {
        require(isUncompressedPoint(rawPoint)) { "Expected an uncompressed P-256 point." }
        return HEADER + rawPoint
    }

    /** The raw point inside [spki], or `null` when [spki] is not an uncompressed P-256 key. */
    fun rawPoint(spki: ByteArray): ByteArray? =
        spki.takeIf { it.size == HEADER.size + RAW_POINT_BYTES && it.copyOf(HEADER.size).contentEquals(HEADER) }
            ?.copyOfRange(HEADER.size, spki.size)
            ?.takeIf(::isUncompressedPoint)

    private fun isUncompressedPoint(bytes: ByteArray): Boolean =
        bytes.size == RAW_POINT_BYTES && bytes.first() == UNCOMPRESSED_POINT
}
