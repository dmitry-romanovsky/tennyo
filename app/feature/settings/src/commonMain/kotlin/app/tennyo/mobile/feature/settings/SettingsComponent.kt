package app.tennyo.mobile.feature.settings

import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsComponent {
    val state: StateFlow<AppSettingsState>

    fun onIntent(intent: SettingsIntent)
    fun onBack()
}

sealed interface SettingsIntent {
    data class ThemeModeChanged(val value: AppThemeMode) : SettingsIntent
    data class ColorSchemeChanged(val value: AppColorScheme) : SettingsIntent
    data class EditorSoftWrapChanged(val value: Boolean) : SettingsIntent
}
