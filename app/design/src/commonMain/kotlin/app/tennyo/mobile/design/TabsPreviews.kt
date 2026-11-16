package app.tennyo.mobile.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.TennyoTheme

@Preview(showBackground = true, widthDp = 360, heightDp = 60)
@Composable
private fun ClosableScrollableTabRowPreview() {
    TennyoTheme {
        ClosableScrollableTabRow(
            items = listOf("Main.kt", "Utils.kt", "build.gradle.kts"),
            selectedIndex = 0,
            itemKey = { it },
            title = { it },
            onSelect = { _, _ -> },
            onClose = {},
            isDirty = { false },
            closeMode = TabCloseMode.SelectedOnly,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 60)
@Composable
private fun ClosableScrollableTabRowEmptyPreview() {
    TennyoTheme {
        ClosableScrollableTabRow<String>(
            items = emptyList(),
            selectedIndex = 0,
            itemKey = { it },
            title = { it },
            onSelect = { _, _ -> },
            onClose = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 96)
@Composable
private fun TennyoBottomNavigationLightPreview() {
    TennyoTheme(themeMode = AppThemeMode.Light) {
        TennyoBottomNavigationPreviewContent()
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 96)
@Composable
private fun TennyoBottomNavigationDarkPreview() {
    TennyoTheme(themeMode = AppThemeMode.Dark) {
        TennyoBottomNavigationPreviewContent()
    }
}

@Composable
private fun TennyoBottomNavigationPreviewContent() {
    TennyoBottomNavigation(
        items = listOf(
            TennyoBottomNavigationItem("Appearance", TennyoIcons.Palette),
            TennyoBottomNavigationItem("Hotkeys", TennyoIcons.Keyboard),
            TennyoBottomNavigationItem("About", TennyoIcons.Info),
        ),
        selectedIndex = 1,
        onSelected = {},
    )
}
