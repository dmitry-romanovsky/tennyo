package app.tennyo.mobile.feature.terminal

import app.tennyo.mobile.link.store.SettingKey
import app.tennyo.mobile.link.store.get
import app.tennyo.mobile.link.store.set
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The terminal's saved preferences: the key bar, edited from the terminal and from Settings, so both read the same
 * [keyBar] flow.
 */
class TerminalPreferences(private val settings: Settings) {
    private val mutableKeyBar = MutableStateFlow(settings[KeyBarKey].sanitized())
    val keyBar: StateFlow<TerminalKeyBarSettings> = mutableKeyBar.asStateFlow()

    fun edit(edit: KeyBarEdit) {
        val current = mutableKeyBar.value
        val updated = when (edit) {
            is KeyBarEdit.AddHotkey -> current.withHotkey(edit.hotkey)
            is KeyBarEdit.RemoveHotkey -> current.withoutHotkey(edit.chordId)
            is KeyBarEdit.SetHotkeyEnabled -> current.withHotkeyEnabled(edit.chordId, edit.enabled)
            is KeyBarEdit.SetBuiltInKeyVisible -> current.withBuiltInKeyVisible(edit.key, edit.visible)
        }.sanitized()
        if (updated == current) return
        settings[KeyBarKey] = updated
        mutableKeyBar.value = updated
    }

    private companion object {
        val KeyBarKey =
            SettingKey.json("terminal.keyBar", TerminalKeyBarSettings.serializer(), TerminalKeyBarSettings())
    }
}

/** One change to the key bar, from the terminal's add-hotkey sheet or the Settings hotkeys page. */
sealed interface KeyBarEdit {
    data class AddHotkey(val hotkey: CustomTerminalHotkey) : KeyBarEdit
    data class RemoveHotkey(val chordId: String) : KeyBarEdit
    data class SetHotkeyEnabled(val chordId: String, val enabled: Boolean) : KeyBarEdit
    data class SetBuiltInKeyVisible(val key: ToggleableTerminalKey, val visible: Boolean) : KeyBarEdit
}
