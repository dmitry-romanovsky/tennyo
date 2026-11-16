package app.tennyo.mobile.feature.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.panScroll
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.design.theme.editorTextStyle
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_cancel
import app.tennyo.mobile.feature.local.generated.resources.local_new_create
import app.tennyo.mobile.feature.local.generated.resources.local_new_file
import app.tennyo.mobile.feature.local.generated.resources.local_new_file_hint
import app.tennyo.mobile.feature.local.generated.resources.local_new_folder
import app.tennyo.mobile.feature.local.generated.resources.local_packages_installing
import app.tennyo.mobile.feature.local.generated.resources.local_packages_requirements_skipped
import app.tennyo.mobile.feature.local.generated.resources.local_packages_requirements_unreadable
import app.tennyo.mobile.feature.local.generated.resources.local_packages_resolving
import app.tennyo.mobile.feature.local.generated.resources.local_run_failed
import app.tennyo.mobile.feature.local.generated.resources.local_run_install_package
import app.tennyo.mobile.feature.local.generated.resources.local_run_no_output
import app.tennyo.mobile.feature.local.generated.resources.local_run_running
import app.tennyo.mobile.feature.local.generated.resources.local_run_succeeded
import app.tennyo.mobile.feature.local.generated.resources.local_run_timed_out
import org.jetbrains.compose.resources.stringResource

/** New file and new folder, above the navigator's listing; both create in the directory it shows. */
@Composable
internal fun NavigatorCreateActions(
    onCreate: (name: String, directory: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        TennyoButton(
            label = stringResource(Res.string.local_new_file),
            icon = TennyoIcons.Add,
            onClick = { asking = false },
            style = TennyoButtonStyle.Secondary,
            size = TennyoButtonSize.Small,
        )
        TennyoButton(
            label = stringResource(Res.string.local_new_folder),
            icon = TennyoIcons.Folder,
            onClick = { asking = true },
            style = TennyoButtonStyle.Secondary,
            size = TennyoButtonSize.Small,
        )
    }
    asking?.let { directory ->
        NewEntryDialog(
            directory = directory,
            onCreate = { name ->
                asking = null
                onCreate(name, directory)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun NewEntryDialog(
    directory: Boolean,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (directory) Res.string.local_new_folder else Res.string.local_new_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.local_new_file_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(Res.string.local_new_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.local_cancel)) }
        },
    )
}

/**
 * The last run and the install of its packages, in a sheet over the editor: progress while either works, then what the
 * run printed, with a way to install the package it could not import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunOutputSheet(
    run: LocalRunState?,
    packages: PackageInstall?,
    requirementsRejectedLine: Int?,
    onInstallPackage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        RunOutputContent(run, packages, requirementsRejectedLine, onInstallPackage)
    }
}

@Composable
private fun RunOutputContent(
    run: LocalRunState?,
    packages: PackageInstall?,
    requirementsRejectedLine: Int?,
    onInstallPackage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.large, vertical = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        requirementsRejectedLine?.let { line ->
            Text(
                text = if (line == 0) {
                    stringResource(Res.string.local_packages_requirements_unreadable)
                } else {
                    stringResource(Res.string.local_packages_requirements_skipped, line)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run?.let { RunTitle(it) }
        when (packages) {
            is PackageInstall.Resolving -> PackageProgress(stringResource(Res.string.local_packages_resolving))
            is PackageInstall.Installing -> PackageProgress(stringResource(Res.string.local_packages_installing))
            is PackageInstall.Failed -> Text(
                text = packageFailureText(packages.failure, packages.detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Unit
        }
        if (packages == null && run?.result?.missingModule != null) {
            TennyoButton(
                label = stringResource(Res.string.local_run_install_package),
                onClick = onInstallPackage,
                style = TennyoButtonStyle.Secondary,
                size = TennyoButtonSize.Small,
            )
        }
        run?.result?.let {
            RunOutputPanel(output = it.output.ifEmpty { stringResource(Res.string.local_run_no_output) })
        }
    }
}

@Composable
private fun RunTitle(run: LocalRunState, modifier: Modifier = Modifier) {
    val result = run.result
    Text(
        text = when (result?.outcome) {
            null -> stringResource(Res.string.local_run_running, run.path)
            PythonRunOutcome.SUCCEEDED -> stringResource(Res.string.local_run_succeeded, run.path)
            PythonRunOutcome.FAILED -> stringResource(Res.string.local_run_failed, run.path)
            PythonRunOutcome.TIMED_OUT -> stringResource(Res.string.local_run_timed_out)
        },
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = if (result?.outcome == PythonRunOutcome.SUCCEEDED || result == null) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (run.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PackageProgress(label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** Output that pans on both axes with one drag and opens at its end, where a traceback or the last line is. */
@Composable
private fun RunOutputPanel(output: String, modifier: Modifier = Modifier) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    LaunchedEffect(output) {
        snapshotFlow { verticalScroll.maxValue }.collect { verticalScroll.scrollTo(it) }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.card,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SelectionContainer {
            Text(
                text = output,
                style = editorTextStyle().copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier
                    .heightIn(max = RunOutputMaxHeight)
                    .panScroll(verticalScroll, horizontalScroll)
                    .padding(Spacing.medium),
            )
        }
    }
}

private val RunOutputMaxHeight = 360.dp

@Preview(showBackground = true, widthDp = 380, heightDp = 420)
@Composable
private fun RunWithMissingModulePreview() {
    TennyoTheme {
        RunOutputContent(
            run = LocalRunState(
                path = "scripts/main.py",
                running = false,
                result = PythonRun(
                    output = "ModuleNotFoundError: No module named 'frontmatter'",
                    outcome = PythonRunOutcome.FAILED,
                    missingModule = "frontmatter",
                ),
            ),
            packages = null,
            requirementsRejectedLine = null,
            onInstallPackage = {},
        )
    }
}
