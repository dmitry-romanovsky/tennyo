package app.tennyo.mobile.feature.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_settings_credentials
import app.tennyo.mobile.feature.local.generated.resources.local_settings_credentials_delete
import app.tennyo.mobile.feature.local.generated.resources.local_settings_credentials_empty
import app.tennyo.mobile.feature.local.generated.resources.local_settings_identity
import app.tennyo.mobile.feature.local.generated.resources.local_settings_identity_email
import app.tennyo.mobile.feature.local.generated.resources.local_settings_identity_explanation
import app.tennyo.mobile.feature.local.generated.resources.local_settings_identity_name
import app.tennyo.mobile.feature.local.generated.resources.local_settings_identity_save
import org.jetbrains.compose.resources.stringResource

/** The Git page of Settings: the commit author and the saved HTTPS credentials. */
@Composable
fun GitSettingsPage(component: GitSettingsComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    GitSettingsPage(state, component::onIntent, modifier)
}

@Composable
private fun GitSettingsPage(
    state: GitSettingsState,
    onIntent: (GitSettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        SettingsGroup(stringResource(Res.string.local_settings_identity)) {
            Text(
                text = stringResource(Res.string.local_settings_identity_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = { onIntent(GitSettingsIntent.ChangeName(it)) },
                label = { Text(stringResource(Res.string.local_settings_identity_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = { onIntent(GitSettingsIntent.ChangeEmail(it)) },
                label = { Text(stringResource(Res.string.local_settings_identity_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            TennyoButton(
                label = stringResource(Res.string.local_settings_identity_save),
                onClick = { onIntent(GitSettingsIntent.SaveIdentity) },
                enabled = state.canSaveIdentity,
                size = TennyoButtonSize.Medium,
            )
        }
        SettingsGroup(stringResource(Res.string.local_settings_credentials)) {
            if (state.credentialHosts.isEmpty()) {
                Text(
                    text = stringResource(Res.string.local_settings_credentials_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.credentialHosts.forEach { host ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(host, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TennyoIconButton(
                        icon = TennyoIcons.Remove,
                        contentDescription = stringResource(Res.string.local_settings_credentials_delete, host),
                        onClick = { onIntent(GitSettingsIntent.DeleteCredentials(host)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.sm)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 520)
@Composable
private fun GitSettingsPagePreview() {
    TennyoTheme {
        GitSettingsPage(
            state = GitSettingsState(name = "Ada", email = "ada@example.com", credentialHosts = listOf("github.com")),
            onIntent = {},
        )
    }
}
