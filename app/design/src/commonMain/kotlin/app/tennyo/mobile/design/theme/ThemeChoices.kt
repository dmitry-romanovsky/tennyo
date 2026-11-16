package app.tennyo.mobile.design.theme

/** Light/dark choice; Auto follows the system. */
enum class AppThemeMode {
    Auto,
    Light,
    Dark,
}

/** Accent source: the platform's dynamic colors, or Tennyo's own scheme. */
enum class AppColorScheme {
    Dynamic,
    TennyoTheme,
}
