package app.tennyo.host.intellij.tests

import app.tennyo.host.engine.discovery.HostIdentityStore
import app.tennyo.host.intellij.HostGlobalSettings
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DiscoverySettingsTest {
    @Test
    fun `current companion id restores from durable store even when xml state is blank`() {
        val settings = HostGlobalSettings(
            companionIdentityStore = object : HostIdentityStore {
                override fun load() = "durable-companion"

                override fun save(companionId: String) = Unit
            },
        )

        settings.loadState(HostGlobalSettings.State())

        assertEquals("durable-companion", settings.currentCompanionIdOrNull())
        assertEquals("durable-companion", settings.state.companionId)
    }

    @Test
    fun `remote access is off by default and survives saving and loading the settings`() {
        val store = object : HostIdentityStore {
            override fun load() = "durable-companion"

            override fun save(companionId: String) = Unit
        }
        val settings = HostGlobalSettings(companionIdentityStore = store)
        assertFalse(settings.isRemoteAccessEnabled())

        settings.setRemoteAccess(true)
        val xml = XmlSerializer.serialize(settings.state)
        val restored = HostGlobalSettings(companionIdentityStore = store)
        restored.loadState(XmlSerializer.deserialize(xml, HostGlobalSettings.State::class.java))

        assertTrue(restored.isRemoteAccessEnabled())
    }
}
