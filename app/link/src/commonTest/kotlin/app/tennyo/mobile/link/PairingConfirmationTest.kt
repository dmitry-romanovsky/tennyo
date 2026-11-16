package app.tennyo.mobile.link

import app.tennyo.contract.PairingChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing selection reducer. Order is the bulk of the secret here, so most of these assert ordering rather than
 * membership.
 */
class PairingConfirmationTest {

    @Test
    fun `taps are recorded in tap order rather than grid order`() {
        val state = confirmation()
            .toggled("Zephyr")
            .toggled("Cedar")
            .toggled("Maple")
            .toggled("Anchor")

        assertEquals(listOf("Zephyr", "Cedar", "Maple", "Anchor"), state.selectedWords)
        // Grid order would have produced the sorted sequence; if it ever does, the tap order has become derivable.
        assertFalse(state.selectedWords == state.selectedWords.sorted())
    }

    @Test
    fun `tapping a selected word removes it and keeps the order of the rest`() {
        val state = confirmation()
            .toggled("Zephyr")
            .toggled("Cedar")
            .toggled("Maple")
            .toggled("Cedar")

        assertEquals(listOf("Zephyr", "Maple"), state.selectedWords)
    }

    @Test
    fun `tapping past the secret length is ignored`() {
        val full = confirmation()
            .toggled("Zephyr")
            .toggled("Cedar")
            .toggled("Maple")
            .toggled("Anchor")

        assertEquals(full.selectedWords, full.toggled("Amber").selectedWords)
    }

    @Test
    fun `words outside the grid are ignored`() {
        assertTrue(confirmation().toggled("Nonsense").selectedWords.isEmpty())
    }

    @Test
    fun `selection is complete only at the full secret length`() {
        var state = confirmation()
        assertFalse(state.isComplete)
        listOf("Zephyr", "Cedar", "Maple").forEach { word ->
            state = state.toggled(word)
            assertFalse(state.isComplete)
        }
        assertTrue(state.toggled("Anchor").isComplete)
    }

    @Test
    fun `tapping clears a previous failure message`() {
        val failed = confirmation().copy(issue = PairingIssue.IncorrectSelection)

        assertNull(failed.toggled("Zephyr").issue)
    }

    @Test
    fun `a grid of the wrong size or with repeats is rejected as unusable`() {
        val challenge = confirmation().challenge

        assertTrue(challenge.hasUsableWordGrid())
        assertFalse(challenge.copy(wordGrid = challenge.wordGrid.drop(1)).hasUsableWordGrid())
        // Selection is keyed by word, so a repeated tile would toggle two at once.
        assertFalse(
            challenge.copy(wordGrid = challenge.wordGrid.dropLast(1) + challenge.wordGrid.first())
                .hasUsableWordGrid(),
        )
    }

    private fun confirmation() = PairingConfirmation(
        challenge = PairingChallenge(
            challengeId = "challenge",
            serverChallenge = "server-challenge",
            expiresAtEpochMillis = Long.MAX_VALUE,
            wordGrid = listOf(
                "Amber", "Anchor", "Beacon", "Cedar", "Comet", "Delta", "Ember", "Forest",
                "Golden", "Ivory", "Jasper", "Lagoon", "Maple", "Nimble", "Orbit", "Zephyr",
            ),
            gridSeed = "grid-seed",
            companionEphemeralPublicKey = "companion-key",
        ),
    )
}
