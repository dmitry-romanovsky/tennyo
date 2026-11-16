package app.tennyo.mobile.feature.settings

import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppSettingsStoreTest {
    @Test
    fun missingPreferencesUseAndroidDefaults() {
        val store = AppSettingsStore(MapSettings(), dynamicColorAvailable = true)

        val state = store.load()

        assertEquals(AppThemeMode.Auto, state.themeMode)
        assertEquals(AppColorScheme.TennyoTheme, state.colorScheme)
        assertEquals(AppColorScheme.Dynamic, state.availableColorSchemes.first())
    }

    @Test
    fun missingPreferencesUseIosDefaults() {
        val store = AppSettingsStore(MapSettings(), dynamicColorAvailable = false)

        val state = store.load()

        assertEquals(AppThemeMode.Auto, state.themeMode)
        assertEquals(AppColorScheme.TennyoTheme, state.colorScheme)
        assertFalse(AppColorScheme.Dynamic in state.availableColorSchemes)
    }

    @Test
    fun invalidPreferenceValuesFallBackSafely() {
        val preferences = MapSettings().apply {
            putString(AppSettingKeys.ThemeMode.name, "System")
            putString(AppSettingKeys.ColorScheme.name, "Solarized")
        }
        val store = AppSettingsStore(preferences, dynamicColorAvailable = true)

        val state = store.load()

        assertEquals(AppThemeMode.Auto, state.themeMode)
        assertEquals(AppColorScheme.TennyoTheme, state.colorScheme)
    }

    @Test
    fun unavailableDynamicPreferenceFallsBackToTennyoTheme() {
        val preferences = MapSettings().apply {
            putString(AppSettingKeys.ColorScheme.name, AppColorScheme.Dynamic.name)
        }
        val store = AppSettingsStore(preferences, dynamicColorAvailable = false)

        assertEquals(AppColorScheme.TennyoTheme, store.load().colorScheme)
    }

    @Test
    fun savedAppearanceSettingsAreLoaded() {
        val store = AppSettingsStore(MapSettings(), dynamicColorAvailable = true)

        store.saveThemeMode(AppThemeMode.Dark)
        store.saveColorScheme(AppColorScheme.TennyoTheme)
        val state = store.load()

        assertEquals(AppThemeMode.Dark, state.themeMode)
        assertEquals(AppColorScheme.TennyoTheme, state.colorScheme)
    }

    /** A phone upgrading from a build that shipped seven schemes and four modes must not be left on one of them. */
    @Test
    fun aStoredSchemeOrModeThatNoLongerExistsFallsBackToTheDefault() {
        val preferences = MapSettings().apply {
            putString(AppSettingKeys.ColorScheme.name, "Rider")
            putString(AppSettingKeys.ThemeMode.name, "Darcula")
        }

        val state = AppSettingsStore(preferences, dynamicColorAvailable = true).load()

        assertEquals(AppColorScheme.TennyoTheme, state.colorScheme)
        assertEquals(AppThemeMode.Auto, state.themeMode)
    }
}
