package app.tennyo.host.engine.pairing

import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodeOsPaths
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.asText
import app.tennyo.host.engine.platform.jsOptions
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file-backed pair store: what it keeps across restarts, and the cases that decide whether a phone may reconnect —
 * a replayed secret, a wrong one, an expired record, and a revoked pair.
 */
class NodeRememberedPairStoreTest {
    private lateinit var directory: String
    private lateinit var store: NodeRememberedPairStore

    @BeforeTest
    fun setUp() {
        directory = NodePath.join(NodeOsPaths.tmpdir(), "tennyo-pairs-${Random.nextLong()}")
        store = NodeRememberedPairStore(directory)
    }

    @AfterTest
    fun tearDown() {
        NodeFs.rmSync(directory, jsOptions("recursive" to true, "force" to true))
    }

    @Test
    fun `a saved pair is found again by a store built fresh over the same directory`() {
        val record = record("pair-1", "secret-1")

        store.save(record)

        val reopened = NodeRememberedPairStore(directory)
        assertEquals(record.pairId, reopened.find("pair-1", NOW)?.pairId)
        assertEquals(listOf("Pixel"), reopened.summaries(NOW).map { it.deviceLabel })
    }

    @Test
    fun `an expired pair is not found, and is dropped rather than left to linger`() {
        store.save(record("pair-1", "secret-1", expiresAt = NOW - 1))

        assertNull(store.find("pair-1", NOW))
        assertTrue(NodeRememberedPairStore(directory).summaries(NOW).isEmpty())
    }

    @Test
    fun `rotating the pair needs the current secret, and a wrong one forgets the pair`() {
        store.save(record("pair-1", "secret-1"))

        val wrong = store.replace("pair-1", "not-the-secret", record("pair-2", "secret-2"), NOW)

        assertFalse(wrong, "a rotation with the wrong secret was accepted")
        assertNull(store.find("pair-1", NOW), "the pair survived a rotation attempt with the wrong secret")
    }

    @Test
    fun `the old pair survives a rotation only to be retried once, which retires it`() {
        store.save(record("pair-1", "secret-1"))

        assertTrue(store.replace("pair-1", "secret-1", record("pair-2", "secret-2"), NOW))
        assertNotNull(store.find("pair-2", NOW))

        // The reply carrying pair-2 may have been lost, so pair-1 is accepted one more time.
        assertTrue(store.replace("pair-1", "secret-1", record("pair-3", "secret-3"), NOW))

        assertNull(store.find("pair-1", NOW), "the old pair id still works after its retry")
        assertNull(store.find("pair-2", NOW), "the pair whose reply was lost still works")
        assertFalse(
            store.replace("pair-1", "secret-1", record("pair-4", "secret-4"), NOW),
            "the rotation was accepted a third time",
        )
    }

    @Test
    fun `a revoked pair is gone, and revoking it again says so`() {
        store.save(record("pair-1", "secret-1"))

        assertTrue(store.revoke("pair-1"))
        assertFalse(store.revoke("pair-1"))
        assertNull(store.find("pair-1", NOW))
    }

    @Test
    fun `an unreadable file is treated as no pairs rather than failing every reconnect`() {
        store.save(record("pair-1", "secret-1"))
        NodeFs.writeFileSync(
            NodePath.join(directory, "remembered-pairs.json"),
            "{ not json",
            jsOptions("encoding" to "utf8"),
        )

        assertTrue(store.summaries(NOW).isEmpty())
        assertNull(store.find("pair-1", NOW))
    }

    @Test
    fun `the file holds only this host's records and nothing it was not given`() {
        store.save(record("pair-1", "secret-1"))

        val text = NodeFs.readFileSync(NodePath.join(directory, "remembered-pairs.json")).asText()

        assertTrue("pair-1" in text, "the record was not written")
        assertEquals(1, text.split("\"pairId\"").size - 1, "more than one record was written: $text")
    }

    private fun record(
        pairId: String,
        pairSecret: String,
        expiresAt: Long = NOW + DAY_MILLIS,
    ) = RememberedPairRecord(
        pairId = pairId,
        pairSecret = pairSecret,
        deviceKeyHash = "device-key-hash",
        deviceLabel = "Pixel",
        companionId = "companion",
        createdAtEpochMillis = NOW,
        lastConfirmedAtEpochMillis = NOW,
        expiresAtEpochMillis = expiresAt,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
