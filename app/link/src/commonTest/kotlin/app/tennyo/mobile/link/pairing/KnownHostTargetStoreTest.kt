package app.tennyo.mobile.link.pairing

import app.tennyo.contract.KnownHostDescriptor
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownHostTargetStoreTest {
    private val store = SettingsKnownHostTargetStore(MapSettings())

    @Test
    fun savesAndRestoresMetadata() {
        val target = KnownHostTarget(
            companionId = "companion-1",
            companionLabel = "Studio",
            lastProjectName = "Tennyo",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = 42L,
            discoveryTransportFingerprint = "fingerprint",
            lastResolvedHost = "https://192.168.1.10:4567",
            lastKnownNetworkLabel = "Home Wi-Fi",
            ticketEndpoints = listOf("https://192.168.1.10:4567", "https://studio.example:4567"),
            addedEndpoints = listOf("https://manual.example:4567"),
        )

        store.save(target)

        assertEquals(target, store.get(target.companionId))
        assertEquals(listOf(target), store.list())
    }

    @Test
    fun descriptorUpdateKeepsExistingLabelAndProjectWhenDescriptorOmitsThem() {
        val existing = KnownHostTarget(
            companionId = "companion-1",
            companionLabel = "Studio",
            lastProjectName = "Tennyo",
            hostVersion = "2026.2",
            lastUsedAtEpochMillis = 42L,
            discoveryTransportFingerprint = "old-fingerprint",
            lastResolvedHost = "https://192.168.1.10:4567",
            ticketEndpoints = listOf("https://192.168.1.10:4567"),
            addedEndpoints = listOf("https://manual.example:4567"),
        )
        store.save(existing)

        store.updateFromDescriptor(
            descriptor = KnownHostDescriptor(
                companionId = existing.companionId,
                companionLabel = "",
                projectName = "",
                hostVersion = "2026.3",
                lastUsedAtEpochMillis = 99L,
            ),
            transportFingerprint = "new-fingerprint",
            resolvedHost = null,
        )

        assertEquals(
            existing.copy(
                hostVersion = "2026.3",
                discoveryTransportFingerprint = "new-fingerprint",
            ),
            store.get(existing.companionId),
        )
    }

    @Test
    fun removeDeletesStoredTarget() {
        store.save(target())

        store.remove("companion-1")

        assertNull(store.get("companion-1"))
    }

    private fun target() = KnownHostTarget(
        companionId = "companion-1",
        companionLabel = "Studio",
        hostVersion = "2026.3",
        lastUsedAtEpochMillis = 42L,
        discoveryTransportFingerprint = "fingerprint",
    )
}
