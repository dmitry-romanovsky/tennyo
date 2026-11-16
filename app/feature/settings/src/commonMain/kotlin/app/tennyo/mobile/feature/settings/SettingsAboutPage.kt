package app.tennyo.mobile.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoAppBrand
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.settings.generated.resources.Res
import app.tennyo.mobile.feature.settings.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource

/** App identity and version. */
@Composable
internal fun SettingsAboutPage(
    appVersionName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        TennyoAppBrand(label = stringResource(Res.string.app_name))
        AppVersionFooter(appVersionName)
    }
}

@Preview(widthDp = 400, heightDp = 240)
@Composable
private fun SettingsAboutPagePreview() {
    TennyoTheme {
        SettingsAboutPage(
            appVersionName = "1.0",
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}
