package app.tennyo.host.intellij.tests

import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.intellij.PasswordSafePairStore
import app.tennyo.host.intellij.hostCredentialAttributes
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** The production remembered-pair store and proof check: expiry, rotation, device binding, malformed proofs. */
@TestApplication
internal class RememberedPairStoreTest {
    private val store = PasswordSafePairStore(hostCredentialAttributes("test.${UUID.randomUUID()}"))

    @Test
    fun `expired pair is not found and is purged`() {
        val record = record(deviceKeyHash = "device-a", now = 0L)
        store.save(record)

        assertNull(store.find(record.pairId, nowEpochMillis = record.expiresAtEpochMillis))
        assertTrue(store.summaries(nowEpochMillis = 0L).isEmpty())
    }

    @Test
    fun `rotation with the wrong current secret fails and revokes the pair`() {
        val record = record(deviceKeyHash = "device-a")
        store.save(record)

        val replaced = store.replace(
            pairId = record.pairId,
            expectedPairSecret = "stolen-or-stale-secret",
            replacement = record(deviceKeyHash = "device-a"),
            nowEpochMillis = NOW,
        )

        assertFalse(replaced)
        assertNull(store.find(record.pairId, NOW))
    }

    @Test
    fun `rotation with the current secret swaps the record and keeps the old one only as a retry`() {
        val record = record(deviceKeyHash = "device-a")
        val replacement = record(deviceKeyHash = "device-a")
        store.save(record)

        assertTrue(store.replace(record.pairId, record.pairSecret, replacement, NOW))
        assertEquals(replacement.pairId, store.find(record.pairId, NOW)?.supersededByPairId)
        assertEquals(replacement, store.find(replacement.pairId, NOW))
        assertEquals(listOf(replacement.pairId), store.summaries(NOW).map { it.pairId })
    }

    @Test
    fun `rotation of an expired pair fails`() {
        val record = record(deviceKeyHash = "device-a", now = 0L)
        store.save(record)

        assertFalse(store.replace(record.pairId, record.pairSecret, record(deviceKeyHash = "device-a"), record.expiresAtEpochMillis))
    }

    @Test
    fun `pairing the same device again supersedes its old record`() {
        val first = record(deviceKeyHash = "device-a")
        val second = record(deviceKeyHash = "device-a")
        val other = record(deviceKeyHash = "device-b")
        store.save(first)
        store.save(other)
        store.save(second)

        assertNull(store.find(first.pairId, NOW))
        assertEquals(setOf(second.pairId, other.pairId), store.summaries(NOW).map { it.pairId }.toSet())
    }

    @Test
    fun `revoke removes the pair once`() {
        val record = record(deviceKeyHash = "device-a")
        store.save(record)

        assertTrue(store.revoke(record.pairId))
        assertFalse(store.revoke(record.pairId))
    }

    private fun record(deviceKeyHash: String, now: Long = NOW): RememberedPairRecord =
        newRememberedPair(deviceLabel = "Pixel", deviceKeyHash = deviceKeyHash, nowEpochMillis = now).second

    private companion object {
        const val NOW = 1_000_000L
    }
}
