package app.tennyo.mobile.feature.settings

import androidx.compose.runtime.Composable
import app.tennyo.mobile.design.theme.AppColorScheme
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.feature.settings.generated.resources.Res
import app.tennyo.mobile.feature.settings.generated.resources.color_scheme_dynamic
import app.tennyo.mobile.feature.settings.generated.resources.color_scheme_tennyo_theme
import app.tennyo.mobile.feature.settings.generated.resources.theme_mode_auto
import app.tennyo.mobile.feature.settings.generated.resources.theme_mode_dark
import app.tennyo.mobile.feature.settings.generated.resources.theme_mode_light
import org.jetbrains.compose.resources.stringResource
data class AppSettingsState(
    val themeMode: AppThemeMode = AppThemeMode.Auto,
    val colorScheme: AppColorScheme = AppColorScheme.Dynamic,
    val availableColorSchemes: List<AppColorScheme> = AppColorScheme.entries,
    /** Long lines wrap at the screen edge instead of scrolling sideways, in every code editor. */
    val editorSoftWrap: Boolean = false,
)

@Composable
fun AppColorScheme.displayName(): String = stringResource(
    when (this) {
        AppColorScheme.Dynamic -> Res.string.color_scheme_dynamic
        AppColorScheme.TennyoTheme -> Res.string.color_scheme_tennyo_theme
    },
)

@Composable
fun AppThemeMode.displayName(): String = stringResource(
    when (this) {
        AppThemeMode.Auto -> Res.string.theme_mode_auto
        AppThemeMode.Light -> Res.string.theme_mode_light
        AppThemeMode.Dark -> Res.string.theme_mode_dark
    },
)
