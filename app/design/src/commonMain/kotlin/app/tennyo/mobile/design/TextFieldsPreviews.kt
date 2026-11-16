package app.tennyo.mobile.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme

@Preview(name = "Prompt field states", widthDp = 420, showBackground = true)
@Composable
private fun TennyoPromptTextFieldPreview() {
    TennyoPromptTextFieldPreviewContent(AppThemeMode.Light)
}

@Preview(name = "Prompt field states dark", widthDp = 420, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun TennyoPromptTextFieldDarkPreview() {
    TennyoPromptTextFieldPreviewContent(AppThemeMode.Dark)
}

@Composable
private fun TennyoPromptTextFieldPreviewContent(themeMode: AppThemeMode) {
    TennyoTheme(themeMode = themeMode) {
        Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            TennyoPromptTextField("", {}, placeholder = "Ask AI")
            TennyoPromptTextField("Review this change\nand run the tests", {}, placeholder = "Ask AI")
            TennyoPromptTextField("AI is working…", {}, enabled = false, placeholder = "Ask AI")
        }
    }
}


