package app.tennyo.mobile.link.store

import com.russhwolf.settings.MapSettings
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingKeyTest {
    private enum class Mode { First, Second }

    @Serializable
    private data class Layout(val columns: Int)

    private val settings = MapSettings()

    @Test
    fun missingValuesReadAsTheirDefaults() {
        assertEquals(true, settings[SettingKey.boolean("flag", default = true)])
        assertEquals(Mode.Second, settings[SettingKey.enum("mode", Mode.Second)])
        assertEquals(Layout(4), settings[SettingKey.json("layout", Layout.serializer(), Layout(4))])
    }

    @Test
    fun writtenValuesReadBack() {
        val mode = SettingKey.enum("mode", Mode.First)
        val layout = SettingKey.json("layout", Layout.serializer(), Layout(1))

        settings[mode] = Mode.Second
        settings[layout] = Layout(3)

        assertEquals(Mode.Second, settings[mode])
        assertEquals(Layout(3), settings[layout])
    }

    @Test
    fun unreadableStoredValuesFallBackToTheDefault() {
        settings.putString("mode", "Removed")
        settings.putString("layout", "{not json")

        assertEquals(Mode.First, settings[SettingKey.enum("mode", Mode.First)])
        assertEquals(Layout(2), settings[SettingKey.json("layout", Layout.serializer(), Layout(2))])
    }
}
