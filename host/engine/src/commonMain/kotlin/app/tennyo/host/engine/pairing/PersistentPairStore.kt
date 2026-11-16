package app.tennyo.host.engine.pairing

import app.tennyo.crypto.constantTimeEquals
import app.tennyo.host.engine.discovery.DiscoveryChallengeStore
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

/**
 * The remembered-pair rules over whatever a host can persist: expiry, supersession, and a secret compared in constant
 * time. Every host keeps the same records with the same lifetime; only [load] and [store] differ, so a phone that
 * reconnects to IntelliJ and to VS Code is forgotten at the same moment on both.
 *
 * A rotation keeps the pair it replaced for one discovery challenge lifetime, or until the new pair is first used for a
 * rotation of its own: a reply lost on a bad connection leaves the phone holding the old pair, and dropping it at once
 * would strand a phone that is away from home. A retry with the old pair succeeds once; the old pair then dies.
 *
 * An expired record is dropped as soon as it is looked at rather than on a timer, so a store that is never read never
 * grows a background job to prune it.
 */
abstract class PersistentPairStore : RememberedPairStore {
    private val lock = HostMonitor()

    /** Every record the host has kept, in no particular order. A store it cannot read answers empty. */
    protected abstract fun load(): List<RememberedPairRecord>

    /** Replaces everything the host has kept with [records]. */
    protected abstract fun store(records: List<RememberedPairRecord>)

    override fun save(record: RememberedPairRecord) = lock.guarded {
        store(load().filterNot { it.isSupersededBy(record) } + record)
    }

    override fun find(pairId: String, nowEpochMillis: Long): RememberedPairRecord? = lock.guarded {
        val records = load()
        val record = records.firstOrNull { it.pairId == pairId } ?: return@guarded null
        if (record.expiresAtEpochMillis > nowEpochMillis) return@guarded record
        store(records - record)
        null
    }

    /**
     * Rotates the pair only for a client that proved it holds the current secret. A mismatch drops the record: the
     * pair is either replayed or compromised, and either way the phone must pair again.
     */
    override fun replace(
        pairId: String,
        expectedPairSecret: String,
        replacement: RememberedPairRecord,
        nowEpochMillis: Long,
    ): Boolean = lock.guarded {
        val records = load()
        val current = records.firstOrNull { it.pairId == pairId } ?: return@guarded false
        if (current.expiresAtEpochMillis <= nowEpochMillis ||
            !constantTimeEquals(current.pairSecret, expectedPairSecret)
        ) {
            store(records - current)
            return@guarded false
        }
        // A pair that is already the grace copy is spent by this rotation; only a live pair leaves one behind.
        val previous = current.takeIf { it.supersededByPairId == null }?.copy(
            expiresAtEpochMillis = minOf(current.expiresAtEpochMillis, nowEpochMillis + ROTATION_GRACE_MILLIS),
            supersededByPairId = replacement.pairId,
        )
        val kept = records.filterNot { it.pairId == pairId || it.isSupersededBy(replacement) }
        store(kept + listOfNotNull(previous) + replacement)
        true
    }

    override fun summaries(nowEpochMillis: Long): List<RememberedPairSummary> = lock.guarded {
        val records = load()
        val active = records.filter { it.expiresAtEpochMillis > nowEpochMillis }
        if (active.size != records.size) store(active)
        active.filter { it.supersededByPairId == null }
            .sortedBy { it.deviceLabel.lowercase() }
            .map(RememberedPairRecord::toSummary)
    }

    override fun revoke(pairId: String): Boolean = lock.guarded {
        val records = load()
        val remaining = records.filterNot { it.pairId == pairId || it.supersededByPairId == pairId }
        if (remaining.size == records.size) return@guarded false
        store(remaining)
        true
    }
}

private const val ROTATION_GRACE_MILLIS = DiscoveryChallengeStore.CHALLENGE_TTL_MILLIS

internal fun RememberedPairRecord.toSummary(): RememberedPairSummary = RememberedPairSummary(
    pairId = pairId,
    deviceLabel = deviceLabel,
    companionId = companionId,
    lastProjectName = lastProjectName,
    lastHostVersion = lastHostVersion,
    lastUsedAtEpochMillis = lastUsedAtEpochMillis,
    lastKnownNetworkLabel = lastKnownNetworkLabel,
    expiresAtEpochMillis = expiresAtEpochMillis,
)
