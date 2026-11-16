package app.tennyo.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HostShortIdTest {

    @Test
    fun derivesTwelveLowercaseHexCharacters() {
        val id = HostShortId.of(SAMPLE_FINGERPRINT)

        assertEquals(12, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, "unexpected characters in $id")
    }

    @Test
    fun isStableForTheSameFingerprint() {
        assertEquals(HostShortId.of(SAMPLE_FINGERPRINT), HostShortId.of(SAMPLE_FINGERPRINT))
    }

    @Test
    fun separatesDifferentFingerprints() {
        assertNotEquals(HostShortId.of(SAMPLE_FINGERPRINT), HostShortId.of(OTHER_FINGERPRINT))
    }

    /** Frozen so a host and a phone on different targets keep agreeing on the id the user compares. */
    @Test
    fun matchesTheFrozenVector() {
        assertEquals("ba7816bf8f01", HostShortId.of("abc"))
    }

    @Test
    fun groupsTheDisplayFormFourByFour() {
        assertEquals("abcd 1234 ef56", HostShortId.display("abcd1234ef56"))
    }

    private companion object {
        const val SAMPLE_FINGERPRINT = "tYRnHkuT6CF0VoVeJ6oVUmR6KpHPRmGSk8Lp0iGdGio"
        const val OTHER_FINGERPRINT = "9mQ1cZ3rLpVtXyB2nK7sD4fG6hJ8kL0mN1oP3qR5sT8"
    }
}
