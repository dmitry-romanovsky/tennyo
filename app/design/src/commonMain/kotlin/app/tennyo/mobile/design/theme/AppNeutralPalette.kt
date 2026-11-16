package app.tennyo.mobile.design.theme

enum class AppNeutralPalette(val darkTheme: Boolean) {
    IslandLight(darkTheme = false),
    IslandDark(darkTheme = true),
}

fun AppThemeMode.resolveAppTheme(systemDarkTheme: Boolean): AppNeutralPalette =
    when (this) {
        AppThemeMode.Auto -> if (systemDarkTheme) AppNeutralPalette.IslandDark else AppNeutralPalette.IslandLight
        AppThemeMode.Light -> AppNeutralPalette.IslandLight
        AppThemeMode.Dark -> AppNeutralPalette.IslandDark
    }
