package app.tennyo.mobile.feature.settings

import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.link.store.SettingKey
import app.tennyo.mobile.link.store.get
import app.tennyo.mobile.link.store.set
import com.russhwolf.settings.Settings

/**
 * Appearance preferences. A scheme the platform cannot show — Dynamic, where the OS has no wallpaper colours —
 * reads as the default, and so does an unrecognised stored scheme or mode.
 */
class AppSettingsStore(private val settings: Settings, dynamicColorAvailable: Boolean) {
    val availableColorSchemes: List<AppColorScheme> =
        AppColorScheme.entries.filter { it != AppColorScheme.Dynamic || dynamicColorAvailable }

    fun load(): AppSettingsState =
        AppSettingsState(
            themeMode = settings[AppSettingKeys.ThemeMode],
            colorScheme = settings[AppSettingKeys.ColorScheme].takeIf { it in availableColorSchemes }
                ?: DEFAULT_COLOR_SCHEME,
            availableColorSchemes = availableColorSchemes,
            editorSoftWrap = settings[AppSettingKeys.EditorSoftWrap],
        )

    fun saveThemeMode(themeMode: AppThemeMode) {
        settings[AppSettingKeys.ThemeMode] = themeMode
    }

    fun saveColorScheme(colorScheme: AppColorScheme) {
        settings[AppSettingKeys.ColorScheme] =
            colorScheme.takeIf { it in availableColorSchemes } ?: DEFAULT_COLOR_SCHEME
    }

    fun saveEditorSoftWrap(softWrap: Boolean) {
        settings[AppSettingKeys.EditorSoftWrap] = softWrap
    }

    private companion object {
        val DEFAULT_COLOR_SCHEME = AppColorScheme.TennyoTheme
    }
}

internal object AppSettingKeys {
    val ThemeMode = SettingKey.enum("theme.mode", AppThemeMode.Auto)
    val ColorScheme = SettingKey.enum("theme.colorScheme", AppColorScheme.TennyoTheme)
    val EditorSoftWrap = SettingKey.boolean("editor.softWrap", false)
}
