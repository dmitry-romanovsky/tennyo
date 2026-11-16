package app.tennyo.mobile.link.pairing

import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedPairStoreTest {
    @Test
    fun canonicalStorageKeyIgnoresEphemeralTransportFingerprint() {
        val first = artifact(fingerprint = "tls-pin-1")
        val second = artifact(fingerprint = "tls-pin-2")

        assertEquals(first.canonicalRememberedPairStorageKey(), second.canonicalRememberedPairStorageKey())
    }

    @Test
    fun canonicalStorageKeyUsesOnlyStableCompanionIdentity() {
        val first = artifact(projectPath = "/Users/me/ProjectA")
        val second = artifact(projectPath = "/Users/me/ProjectB")

        assertEquals(first.canonicalRememberedPairStorageKey(), second.canonicalRememberedPairStorageKey())
    }

    @Test
    fun canonicalStorageKeyIsMissingWithoutCompanionId() {
        val artifact = artifact(companionId = null)

        assertNull(artifact.canonicalRememberedPairStorageKey())
    }

    @Test
    fun companionStorageKeyUsesCompanionPrefix() {
        assertEquals("companion:companion-1", companionRememberedPairStorageKey("companion-1"))
    }

    private fun artifact(
        fingerprint: String = "tls-pin",
        projectPath: String = "/Users/me/Project",
        projectName: String = "Project",
        companionId: String? = "companion-1",
    ): LinkTicket =
        LinkTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session",
            token = "token",
            fingerprint = fingerprint,
            projectName = projectName,
            projectPath = projectPath,
            companionId = companionId,
        )
}
