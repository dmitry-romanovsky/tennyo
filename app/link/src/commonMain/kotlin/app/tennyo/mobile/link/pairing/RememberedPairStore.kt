package app.tennyo.mobile.link.pairing

import app.tennyo.mobile.link.LinkTicket

data class RememberedPairMaterial(
    val pairId: String,
    val pairSecret: String,
    val expiresAtEpochMillis: Long,
    val deviceLabel: String,
    val companionId: String? = null,
)

data class RememberedPair(
    val pairId: String,
    val pairSecret: String,
    val expiresAtEpochMillis: Long,
)

interface RememberedPairStore {
    fun load(artifact: LinkTicket): RememberedPair?
    fun save(artifact: LinkTicket, material: RememberedPairMaterial)
    fun remove(artifact: LinkTicket)
    fun removeByCompanionId(companionId: String)
}

internal fun LinkTicket.canonicalRememberedPairStorageKey(): String? =
    companionId?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::companionRememberedPairStorageKey)

internal fun companionRememberedPairStorageKey(companionId: String): String =
    "companion:${companionId.trim()}"
