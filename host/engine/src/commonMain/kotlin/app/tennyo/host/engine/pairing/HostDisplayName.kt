package app.tennyo.host.engine.pairing

import app.tennyo.crypto.secureRandomBytes

/**
 * The name a host shows while its pairing window is open — "Amber Harbour" — so the user can pick their own machine
 * out of a list. It is drawn fresh for every window and never persisted, and the protocol trusts none of it: two
 * hosts may draw the same name, and the short id beside it is the thing derived from the transport fingerprint.
 *
 * The lists deliberately exclude anything that reads as a real device model or a person's name, so a host in the
 * list cannot be mistaken for a machine the user knows by name.
 */
object HostDisplayName {

    /** An adjective and a noun joined by a space, drawn uniformly from [ADJECTIVES] and [NOUNS]. */
    fun next(randomBytes: (Int) -> ByteArray = ::secureRandomBytes): String {
        val draw = randomBytes(2 * INDEX_BYTES)
        val adjective = ADJECTIVES[indexAt(draw, 0, ADJECTIVES.size)]
        val noun = NOUNS[indexAt(draw, INDEX_BYTES, NOUNS.size)]
        return "$adjective $noun"
    }

    private const val INDEX_BYTES = 4
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFFL

    private fun indexAt(draw: ByteArray, offset: Int, bound: Int): Int {
        var value = 0L
        for (index in offset until offset + INDEX_BYTES) {
            value = (value shl BYTE_BITS) or (draw[index].toLong() and BYTE_MASK)
        }
        return (value % bound).toInt()
    }

    /** Public so the curation rule — no device or person names, no confusable pairs — stays testable. */
    val ADJECTIVES: List<String> = listOf(
        "Amber", "Azure", "Bright", "Bronze", "Calm", "Cobalt", "Copper", "Coral",
        "Crimson", "Dusty", "Emerald", "Golden", "Hidden", "Indigo", "Ivory", "Jade",
        "Lunar", "Marble", "Misty", "Olive", "Onyx", "Pearl", "Quiet", "Rustic",
        "Sapphire", "Scarlet", "Silent", "Silver", "Slate", "Teal", "Velvet", "Violet",
    )

    /** Places rather than objects, so a name never reads as a device model. */
    val NOUNS: List<String> = listOf(
        "Arbour", "Basin", "Bluff", "Canyon", "Cavern", "Cove", "Delta", "Dune",
        "Fjord", "Garden", "Glade", "Harbour", "Hollow", "Island", "Lagoon", "Meadow",
        "Mesa", "Orchard", "Prairie", "Quarry", "Ridge", "River", "Savanna", "Summit",
        "Terrace", "Thicket", "Tundra", "Valley", "Vista", "Willow",
    )
}
