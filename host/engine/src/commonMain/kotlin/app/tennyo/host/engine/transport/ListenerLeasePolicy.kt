package app.tennyo.host.engine.transport

import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.toBase64Url

/** Tracks opaque listener ownership; callers can only release the handle they acquired. */
class ListenerLeasePolicy {
    private val owners = linkedMapOf<String, String>()

    val count: Int get() = owners.size
    val isListenerRequired: Boolean get() = owners.isNotEmpty()

    fun acquire(ownerKind: String): String =
        secureRandomBytes(LEASE_ID_BYTES).toBase64Url().also { owners[it] = ownerKind }

    fun release(leaseId: String): Boolean = owners.remove(leaseId) != null

    fun clear() = owners.clear()

    private companion object {
        const val LEASE_ID_BYTES = 16
    }
}
