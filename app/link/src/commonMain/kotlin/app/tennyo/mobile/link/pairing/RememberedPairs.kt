package app.tennyo.mobile.link.pairing

import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The remembered-pair store, plus the pair a claim announced to the host. The claim and the resume that answers its
 * challenge are two requests; holding the claimed pair between them keeps the resume on the pair the host was told
 * about even if the store changes in between.
 */
class RememberedPairs(val store: RememberedPairStore) {
    private val claimed = mutableMapOf<String, RememberedPair>()
    private val mutex = Mutex()

    /** Loads the pair for [ticket] and holds it for the resume that may follow the claim. */
    suspend fun loadForClaim(ticket: LinkTicket): RememberedPair? {
        val pair = store.load(ticket)
        ticket.canonicalRememberedPairStorageKey()?.let { key ->
            mutex.withLock {
                if (pair == null) claimed.remove(key) else claimed[key] = pair
            }
        }
        return pair
    }

    /** The claim finished without needing a resume, or failed. */
    suspend fun releaseClaim(ticket: LinkTicket) {
        ticket.canonicalRememberedPairStorageKey()?.let { key -> mutex.withLock { claimed.remove(key) } }
    }

    /** The pair the last claim for [ticket] announced, or the stored one. */
    suspend fun forResume(ticket: LinkTicket): RememberedPair? {
        val key = ticket.canonicalRememberedPairStorageKey()
        val claimedPair = key?.let { mutex.withLock { claimed.remove(it) } }
        return claimedPair ?: store.load(ticket)
    }

    suspend fun forget(companionId: String) {
        store.removeByCompanionId(companionId)
        mutex.withLock { claimed.remove(companionRememberedPairStorageKey(companionId)) }
    }
}
