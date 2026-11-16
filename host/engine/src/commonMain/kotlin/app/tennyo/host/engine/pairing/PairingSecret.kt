package app.tennyo.host.engine.pairing

import app.tennyo.contract.PairingWords
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.platform.HostSecureRandom
import kotlin.random.Random

/**
 * The pairing secret the user carries from the IDE to the phone: [PairingWords.SECRET_LENGTH] ordered words, shown in
 * the companion popup and tapped back on the client out of a [PairingWords.GRID_SIZE] grid.
 *
 * Security lives entirely in *which* words and *in what order* — P(16,4) = 43,680, or 15.41 bits, bounded online by
 * the three-attempt cap. The grid is public: the client (and therefore anyone who reached the claim step) sees all
 * sixteen words and can derive all sixteen tile keys. See `docs/pairing-word-grid.md`.
 */
class PairingSecret(
    /** The secret, in the order the popup displays it and the user must tap it. Never sorted. */
    val words: List<String>,
    /** Every tile the client renders: [words] plus decoys, sorted so the secret order is not derivable from it. */
    val wordGrid: List<String>,
    /** Base64url seed deriving each tile's key. Stable for the session so the grid survives challenge rotation. */
    val gridSeed: String,
) {
    init {
        require(words.size == PairingWords.SECRET_LENGTH) {
            "A pairing secret is ${PairingWords.SECRET_LENGTH} words"
        }
        require(wordGrid.size == PairingWords.GRID_SIZE) { "Pairing grid must be ${PairingWords.GRID_SIZE} words" }
        require(wordGrid.toSet().size == wordGrid.size) { "Pairing grid must not repeat a word" }
        require(wordGrid.containsAll(words)) { "Pairing grid must contain every secret word" }
    }

    /** The MAC key for a tapped [selection]; see [SecurityDerivations.pairingSelectionSecret]. */
    fun selectionSecret(selection: List<String>): ByteArray =
        SecurityDerivations.pairingSelectionSecret(gridSeed, selection)

    /** The expected answer — [words] in issue order. */
    fun expectedSelectionSecret(): ByteArray = selectionSecret(words)
}

private const val PAIRING_GRID_SEED_BYTES = 32

/**
 * Draws the secret and the grid from a **single shuffle**: the first [PairingWords.SECRET_LENGTH] words are the
 * secret, the first [PairingWords.GRID_SIZE] are the grid. One uniform draw makes the grid exchangeable, so sorting
 * it destroys every trace of which words were chosen — and secret/decoy distinctness comes for free.
 *
 * The secret keeps the shuffled order. Sorting it, or deriving it from grid positions, would make the tap order
 * readable off the grid and silently collapse the space from P(16,4) = 43,680 to C(16,4) = 1,820.
 */
fun newPairingSecret(random: Random = HostSecureRandom): PairingSecret {
    val shuffled = PAIRING_WORDS.shuffled(random)
    val seedBytes = random.nextBytes(PAIRING_GRID_SEED_BYTES)
    return PairingSecret(
        words = shuffled.take(PairingWords.SECRET_LENGTH),
        wordGrid = shuffled.take(PairingWords.GRID_SIZE).sorted(),
        gridSeed = seedBytes.toBase64Url(),
    )
}

/**
 * Pairing vocabulary, curated by one rule: **at most seven characters, and no two words sharing their first two
 * letters**. That keeps confusable clusters (Sierra/Signal/Silver, Meadow/Merry/Meteor, Brave/Breeze/Bridge) off the
 * grid, and keeps every tile narrow enough for a phone.
 *
 * There is deliberately no adjective/noun split: a known `adjective noun adjective noun` shape would let an attacker
 * infer slot types and collapse the search space roughly fourteen-fold. Digits are excluded for the same reason — a
 * lone number tile identifies itself.
 *
 * Public rather than private so the curation rule is testable.
 */
val PAIRING_WORDS: List<String> = listOf(
    "Agile", "Amber", "Anchor", "Aurora", "Beacon", "Bold", "Brave", "Calm",
    "Cedar", "Clear", "Comet", "Curious", "Daring", "Delta", "Direct", "Drift",
    "Eager", "Echo", "Ember", "Fair", "Field", "Flame", "Forest", "Fresh",
    "Galaxy", "Gentle", "Glowing", "Golden", "Grand", "Happy", "Honest", "Humble",
    "Island", "Ivory", "Jasper", "Jolly", "Keen", "Kind", "Lagoon", "Lively",
    "Lucid", "Maple", "Meadow", "Mighty", "Nimble", "Noble", "Ocean", "Orbit",
    "Patient", "Pebble", "Pixel", "Plain", "Prairie", "Quartz", "Rapid", "Ready",
    "Ribbon", "Rocket", "Saffron", "Sharp", "Sierra", "Smart", "Solid", "Spark",
    "Steady", "Summit", "Swift", "Temple", "Thunder", "Tiny", "Topaz", "True",
    "Urban", "Valley", "Vector", "Vivid", "Voyage", "Warm", "Willow", "Young",
    "Zephyr",
)
