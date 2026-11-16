package app.tennyo.mobile.feature.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.layout.PlatformTopAppBar
import app.tennyo.mobile.design.layout.PlatformTopAppBarSize
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.git.text
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_cancel
import app.tennyo.mobile.feature.local.generated.resources.local_clone_action
import app.tennyo.mobile.feature.local.generated.resources.local_clone_credentials
import app.tennyo.mobile.feature.local.generated.resources.local_clone_failed
import app.tennyo.mobile.feature.local.generated.resources.local_clone_leave_message
import app.tennyo.mobile.feature.local.generated.resources.local_clone_leave_title
import app.tennyo.mobile.feature.local.generated.resources.local_clone_progress
import app.tennyo.mobile.feature.local.generated.resources.local_clone_retry
import app.tennyo.mobile.feature.local.generated.resources.local_clone_starting
import app.tennyo.mobile.feature.local.generated.resources.local_clone_title
import app.tennyo.mobile.feature.local.generated.resources.local_clone_url
import app.tennyo.mobile.feature.local.generated.resources.local_credentials_secret
import app.tennyo.mobile.feature.local.generated.resources.local_credentials_sign_in
import app.tennyo.mobile.feature.local.generated.resources.local_credentials_username
import app.tennyo.mobile.feature.local.generated.resources.local_leave
import app.tennyo.mobile.feature.local.generated.resources.local_stay
import app.tennyo.mobile.feature.local.generated.resources.local_workspace_storage_note
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneWorkspaceScreen(component: CloneWorkspaceComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    if (state.confirmingLeave) {
        AlertDialog(
            onDismissRequest = { component.onIntent(CloneWorkspaceIntent.DismissLeave) },
            confirmButton = {
                TennyoButton(
                    label = stringResource(Res.string.local_leave),
                    onClick = { component.onIntent(CloneWorkspaceIntent.ConfirmLeave) },
                    style = TennyoButtonStyle.Danger,
                )
            },
            dismissButton = {
                TennyoButton(
                    label = stringResource(Res.string.local_stay),
                    onClick = { component.onIntent(CloneWorkspaceIntent.DismissLeave) },
                    style = TennyoButtonStyle.Text,
                )
            },
            title = { Text(stringResource(Res.string.local_clone_leave_title)) },
            text = { Text(stringResource(Res.string.local_clone_leave_message)) },
        )
    }

    Scaffold(
        // Edge to edge, nothing resizes for the keyboard: the form and its button lift above it here.
        modifier = modifier.imePadding().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PlatformTopAppBar(
                title = stringResource(Res.string.local_clone_title),
                onBack = component::onBack,
                actions = {},
                modifier = Modifier.fillMaxWidth(),
                size = PlatformTopAppBarSize.Large,
                bottomContent = null,
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CloneWorkspaceForm(
                state = state,
                onIntent = component::onIntent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ComponentSizes.formMaxWidth)
                    .padding(innerPadding)
                    .padding(horizontal = Spacing.extraLarge),
            )
        }
    }
}

@Composable
private fun CloneWorkspaceForm(
    state: CloneWorkspaceState,
    onIntent: (CloneWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        OutlinedTextField(
            value = state.url,
            onValueChange = { onIntent(CloneWorkspaceIntent.ChangeUrl(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.local_clone_url)) },
            singleLine = true,
            enabled = state.phase !is ClonePhase.Cloning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Text(
            text = stringResource(Res.string.local_workspace_storage_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val phase = state.phase) {
            ClonePhase.Editing -> CloneButton(stringResource(Res.string.local_clone_action), state, onIntent)
            is ClonePhase.Cloning -> CloneProgress(phase.progress, onIntent)
            is ClonePhase.CredentialsNeeded -> CloneCredentialsForm(phase.host, onIntent)
            is ClonePhase.Failed -> {
                TennyoStatusBanner(
                    title = stringResource(Res.string.local_clone_failed),
                    message = phase.fault.text.resolve(),
                    tone = TennyoStatusTone.Failed,
                )
                CloneButton(stringResource(Res.string.local_clone_retry), state, onIntent)
            }
        }
    }
}

@Composable
private fun CloneButton(
    label: String,
    state: CloneWorkspaceState,
    onIntent: (CloneWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    TennyoButton(
        label = label,
        icon = TennyoIcons.Download,
        onClick = { onIntent(CloneWorkspaceIntent.Start) },
        enabled = state.canStart,
        size = TennyoButtonSize.PrimaryAction,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CloneProgress(progress: Float?, onIntent: (CloneWorkspaceIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            text = progress?.let { stringResource(Res.string.local_clone_progress, (it * PERCENT).toInt()) }
                ?: stringResource(Res.string.local_clone_starting),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
            }
            TennyoIconButton(
                icon = TennyoIcons.Close,
                contentDescription = stringResource(Res.string.local_cancel),
                onClick = { onIntent(CloneWorkspaceIntent.Cancel) },
            )
        }
    }
}

@Composable
private fun CloneCredentialsForm(
    host: String,
    onIntent: (CloneWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable { mutableStateOf("") }
    // The secret stays out of saved instance state.
    var secret by remember { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(stringResource(Res.string.local_clone_credentials, host), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.local_credentials_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(stringResource(Res.string.local_credentials_secret)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        TennyoButton(
            label = stringResource(Res.string.local_credentials_sign_in),
            onClick = { onIntent(CloneWorkspaceIntent.SubmitCredentials(username.trim(), secret)) },
            enabled = username.isNotBlank() && secret.isNotEmpty(),
            size = TennyoButtonSize.PrimaryAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val PERCENT = 100

@Preview(showBackground = true, widthDp = 380, heightDp = 320)
@Composable
private fun CloneWorkspaceProgressPreview() {
    TennyoTheme {
        CloneWorkspaceForm(
            state = CloneWorkspaceState(url = "https://example.com/team/app.git", phase = ClonePhase.Cloning(0.4f)),
            onIntent = {},
        )
    }
}
