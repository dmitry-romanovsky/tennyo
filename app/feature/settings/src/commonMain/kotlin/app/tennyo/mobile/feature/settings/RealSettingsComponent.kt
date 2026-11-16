package app.tennyo.mobile.feature.settings

import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.link.store.FeatureStore
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.StateFlow
class RealSettingsComponent(
    componentContext: ComponentContext,
    initialState: AppSettingsState,
    private val onThemeModeChanged: (AppThemeMode) -> AppSettingsState,
    private val onColorSchemeChanged: (AppColorScheme) -> AppSettingsState,
    private val onEditorSoftWrapChanged: (Boolean) -> AppSettingsState,
    private val onBackNav: () -> Unit,
) : SettingsComponent, ComponentContext by componentContext {
    private val store = FeatureStore<AppSettingsState, SettingsMsg, Nothing>(
        initialState = initialState,
        lifecycle = lifecycle,
        reduce = { _, msg ->
            when (msg) {
                is SettingsMsg.Saved -> msg.settings
            }
        },
    )

    override val state: StateFlow<AppSettingsState> = store.state

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ThemeModeChanged -> save(onThemeModeChanged(intent.value))
            is SettingsIntent.ColorSchemeChanged -> save(onColorSchemeChanged(intent.value))
            is SettingsIntent.EditorSoftWrapChanged -> save(onEditorSoftWrapChanged(intent.value))
        }
    }

    override fun onBack() {
        onBackNav()
    }

    private fun save(saved: AppSettingsState) = store.dispatch(SettingsMsg.Saved(saved))
}

/** The root persists every change and hands back what was stored, so the screen always shows saved values. */
internal sealed interface SettingsMsg {
    data class Saved(val settings: AppSettingsState) : SettingsMsg
}
