package app.tennyo.mobile.feature.terminal

import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalPreferencesTest {
    private val ctrlD = CustomTerminalHotkey(TerminalKeyChord('d', TerminalModifiers(ctrl = true)), "EOF")

    @Test
    fun missingKeyBarShowsPageKeysAndNoCustomHotkeys() {
        assertEquals(TerminalKeyBarSettings(), TerminalPreferences(MapSettings()).keyBar.value)
    }

    @Test
    fun editsArePersistedAndSeenByEveryReader() {
        val settings = MapSettings()
        val preferences = TerminalPreferences(settings)

        preferences.edit(KeyBarEdit.AddHotkey(ctrlD))
        preferences.edit(KeyBarEdit.SetHotkeyEnabled(ctrlD.chord.id, enabled = false))
        preferences.edit(KeyBarEdit.SetBuiltInKeyVisible(ToggleableTerminalKey.PageDown, visible = false))

        val expected = TerminalKeyBarSettings(
            hiddenBuiltInKeys = setOf(ToggleableTerminalKey.PageDown),
            customHotkeys = listOf(ctrlD.copy(enabled = false)),
        )
        assertEquals(expected, preferences.keyBar.value)
        assertEquals(expected, TerminalPreferences(settings).keyBar.value)
    }

    @Test
    fun aDuplicateHotkeyChangesNothing() {
        val settings = MapSettings()
        val preferences = TerminalPreferences(settings)
        preferences.edit(KeyBarEdit.AddHotkey(ctrlD))
        val stored = settings.keys.associateWith { settings.getStringOrNull(it) }

        preferences.edit(KeyBarEdit.AddHotkey(ctrlD.copy(name = "Again")))

        assertEquals(listOf(ctrlD), preferences.keyBar.value.customHotkeys)
        assertEquals(stored, settings.keys.associateWith { settings.getStringOrNull(it) })
    }

    @Test
    fun removingAHotkeyKeepsTheOthers() {
        val preferences = TerminalPreferences(MapSettings())
        preferences.edit(KeyBarEdit.AddHotkey(ctrlD))

        preferences.edit(KeyBarEdit.RemoveHotkey(ctrlD.chord.id))

        assertEquals(TerminalKeyBarSettings(), preferences.keyBar.value)
    }

    @Test
    fun corruptKeyBarFallsBackToTheDefault() {
        listOf("{", "null", """{"customHotkeys":"Ctrl+D","hiddenBuiltInKeys":7}""").forEach { corrupt ->
            val settings = MapSettings().apply { putString("terminal.keyBar", corrupt) }

            assertEquals(TerminalKeyBarSettings(), TerminalPreferences(settings).keyBar.value, corrupt)
        }
    }

    @Test
    fun storedKeyBarIsSanitizedOnLoad() {
        val valid = hotkeys(30)
        val stored = TerminalKeyBarSettings(
            customHotkeys = listOf(
                CustomTerminalHotkey(TerminalKeyChord('c', TerminalModifiers(ctrl = true)), "Reserved"),
                valid.first().copy(name = "Duplicate"),
            ) + valid,
        )
        val settings = MapSettings().apply { putString("terminal.keyBar", Json.encodeToString(stored)) }

        assertEquals(
            listOf(valid.first().copy(name = "Duplicate")) + valid.drop(1).take(MAX_CUSTOM_TERMINAL_HOTKEYS - 1),
            TerminalPreferences(settings).keyBar.value.customHotkeys,
        )
    }
}
