package app.tennyo.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Properties of the derivations that the frozen vectors in [SecurityTranscriptVectorTest] do not spell out. */
class SecurityDerivationsTest {
    @Test
    fun selectionSecretDependsOnTapOrder() {
        val forward = SecurityDerivations.pairingSelectionSecret(GRID_SEED, listOf("Zephyr", "Cedar", "Maple", "Anchor"))
        val reversed = SecurityDerivations.pairingSelectionSecret(GRID_SEED, listOf("Anchor", "Maple", "Cedar", "Zephyr"))

        assertFalse(forward.contentEquals(reversed))
    }

    @Test
    fun selectionSecretDependsOnTheSessionGridSeed() {
        val words = listOf("Zephyr", "Cedar", "Maple", "Anchor")

        assertFalse(
            SecurityDerivations.pairingSelectionSecret(GRID_SEED, words)
                .contentEquals(SecurityDerivations.pairingSelectionSecret("another-seed", words)),
        )
    }

    @Test
    fun selectionSecretIsOneFixedWidthTileKeyPerTappedWord() {
        val secret = SecurityDerivations.pairingSelectionSecret(GRID_SEED, listOf("Zephyr", "Cedar", "Maple", "Anchor"))

        // Fixed-width keys are what make the concatenation unambiguous without a prefix-free word list.
        assertEquals(4 * TILE_KEY_BYTES, secret.size)
    }

    @Test
    fun pairMaterialKeyFollowsTheSharedSecret() {
        val first = SecurityDerivations.pairMaterialKey("shared-secret-one".encodeToByteArray(), "server-challenge", "transcript")
        val second = SecurityDerivations.pairMaterialKey("shared-secret-two".encodeToByteArray(), "server-challenge", "transcript")

        // The pair material never travels under anything derived from the tapped words, which is what removes the
        // offline-recovery path a recorded exchange would otherwise offer.
        assertFalse(first.contentEquals(second))
    }

    private companion object {
        const val GRID_SEED = "grid-seed-1"
        const val TILE_KEY_BYTES = 32
    }
}
