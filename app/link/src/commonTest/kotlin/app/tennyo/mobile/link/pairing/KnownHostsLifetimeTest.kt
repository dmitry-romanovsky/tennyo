package app.tennyo.mobile.link.pairing

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pane.nowEpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals

class KnownHostsLifetimeTest {
    private val targets = mutableMapOf<String, KnownHostTarget>()
    private val forgottenPairs = mutableListOf<String>()
    private val knownHosts = KnownHosts(
        store = object : KnownHostTargetStore {
            override fun list(): List<KnownHostTarget> = targets.values.toList()
            override fun get(companionId: String): KnownHostTarget? = targets[companionId]
            override fun save(target: KnownHostTarget) {
                targets[target.companionId] = target
            }

            override fun remove(companionId: String) {
                targets.remove(companionId)
            }

            override fun updateFromDescriptor(
                descriptor: KnownHostDescriptor,
                transportFingerprint: String,
                resolvedHost: String?,
            ) = Unit
        },
        rememberedPairs = object : RememberedPairStore {
            override fun load(artifact: LinkTicket): RememberedPair? = null
            override fun save(artifact: LinkTicket, material: RememberedPairMaterial) = Unit
            override fun remove(artifact: LinkTicket) = Unit
            override fun removeByCompanionId(companionId: String) {
                forgottenPairs += companionId
            }
        },
    )

    @Test
    fun `KnownIdes keeps a 13-day-old target and forgets a 15-day-old one with its pair`() {
        add("recent", lastUsedDaysAgo = 13)
        add("stale", lastUsedDaysAgo = 15)

        assertEquals(listOf("recent"), knownHosts.list().map { it.companionId })
        assertEquals(listOf("stale"), forgottenPairs)
    }

    private fun add(companionId: String, lastUsedDaysAgo: Long) {
        targets[companionId] = KnownHostTarget(
            companionId = companionId,
            companionLabel = "Studio",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = nowEpochMillis() - lastUsedDaysAgo * MILLIS_PER_DAY,
            discoveryTransportFingerprint = "fingerprint-$companionId",
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
