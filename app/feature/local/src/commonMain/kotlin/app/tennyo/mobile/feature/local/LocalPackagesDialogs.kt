package app.tennyo.mobile.feature.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_cancel
import app.tennyo.mobile.feature.local.generated.resources.local_packages_index_chaquopy
import app.tennyo.mobile.feature.local.generated.resources.local_packages_index_pypi
import app.tennyo.mobile.feature.local.generated.resources.local_packages_install
import app.tennyo.mobile.feature.local.generated.resources.local_packages_invalid_requirement
import app.tennyo.mobile.feature.local.generated.resources.local_packages_look_up
import app.tennyo.mobile.feature.local.generated.resources.local_packages_missing_body
import app.tennyo.mobile.feature.local.generated.resources.local_packages_missing_hint
import app.tennyo.mobile.feature.local.generated.resources.local_packages_missing_imports
import app.tennyo.mobile.feature.local.generated.resources.local_packages_missing_title
import app.tennyo.mobile.feature.local.generated.resources.local_packages_network
import app.tennyo.mobile.feature.local.generated.resources.local_packages_no_compatible_wheel
import app.tennyo.mobile.feature.local.generated.resources.local_packages_not_found
import app.tennyo.mobile.feature.local.generated.resources.local_packages_rejected
import app.tennyo.mobile.feature.local.generated.resources.local_packages_requirements_body
import app.tennyo.mobile.feature.local.generated.resources.local_packages_requirements_title
import app.tennyo.mobile.feature.local.generated.resources.local_packages_review_body
import app.tennyo.mobile.feature.local.generated.resources.local_packages_review_item
import app.tennyo.mobile.feature.local.generated.resources.local_packages_review_title
import app.tennyo.mobile.feature.local.generated.resources.local_packages_timed_out
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The first consent, before anything touches the network: the packages `requirements.txt` lists, or a field for the
 * distribution that provides a module a run could not import. [onConfirm] gets the typed name, empty for the file.
 */
@Composable
internal fun PackageRequestDialog(
    source: PackageSource,
    onConfirm: (distribution: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var distribution by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                stringResource(
                    when (source) {
                        is PackageSource.RequirementsFile -> Res.string.local_packages_requirements_title
                        is PackageSource.MissingModule -> Res.string.local_packages_missing_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                when (source) {
                    is PackageSource.RequirementsFile -> {
                        Text(stringResource(Res.string.local_packages_requirements_body))
                        PackageLines(source.requirements.map { it.line to it.line })
                    }

                    is PackageSource.MissingModule -> {
                        Text(stringResource(Res.string.local_packages_missing_imports, source.module))
                        Text(stringResource(Res.string.local_packages_missing_body))
                        OutlinedTextField(
                            value = distribution,
                            onValueChange = { distribution = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.local_packages_missing_hint)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(distribution) },
                enabled = source is PackageSource.RequirementsFile || requirementOrNull(distribution) != null,
            ) {
                Text(stringResource(Res.string.local_packages_look_up))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.local_cancel)) }
        },
    )
}

/** The second consent: every distribution that would be unpacked, with its version and where it comes from. */
@Composable
internal fun PackageReviewDialog(
    resolution: PackageResolution.Resolved,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(Res.string.local_packages_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(stringResource(Res.string.local_packages_review_body))
                PackageLines(
                    resolution.packages.map { item ->
                        item.name to stringResource(
                            Res.string.local_packages_review_item,
                            item.name,
                            item.version,
                            stringResource(item.index.label),
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.local_packages_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.local_cancel)) }
        },
    )
}

/** One monospace line per package, as (key, text); the key is the package's name. */
@Composable
private fun PackageLines(lines: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.heightIn(max = PackageListMaxHeight)) {
        items(lines, key = { it.first }) { (_, text) ->
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

private val PackageIndex.label: StringResource
    get() = when (this) {
        PackageIndex.CHAQUOPY -> Res.string.local_packages_index_chaquopy
        PackageIndex.PYPI -> Res.string.local_packages_index_pypi
    }

/** What went wrong, worded for [failure] and naming [detail], the distribution or requirement it is about. */
@Composable
internal fun packageFailureText(failure: PackageFailure, detail: String): String =
    stringResource(
        when (failure) {
            PackageFailure.NOT_FOUND -> Res.string.local_packages_not_found
            PackageFailure.NO_COMPATIBLE_WHEEL -> Res.string.local_packages_no_compatible_wheel
            PackageFailure.NETWORK -> Res.string.local_packages_network
            PackageFailure.TIMED_OUT -> Res.string.local_packages_timed_out
            PackageFailure.REJECTED -> Res.string.local_packages_rejected
            PackageFailure.INVALID_REQUIREMENT -> Res.string.local_packages_invalid_requirement
        },
        detail,
    )

private val PackageListMaxHeight = 240.dp

private val previewResolution = PackageResolution.Resolved(
    listOf(
        ResolvedPackage("python-frontmatter", "1.1.0", PackageIndex.PYPI),
        ResolvedPackage("pyyaml", "6.0.3", PackageIndex.CHAQUOPY),
    ),
)

@Preview(showBackground = true, widthDp = 380, heightDp = 420)
@Composable
private fun RequirementsDialogPreview() {
    val requirements = listOf("Markdown==2.6.11", "python-frontmatter>=1.0").mapNotNull(::requirementOrNull)
    TennyoTheme {
        PackageRequestDialog(PackageSource.RequirementsFile(requirements), onConfirm = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 420)
@Composable
private fun MissingModuleDialogPreview() {
    TennyoTheme {
        PackageRequestDialog(PackageSource.MissingModule("frontmatter"), onConfirm = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 420)
@Composable
private fun ReviewDialogPreview() {
    TennyoTheme {
        PackageReviewDialog(previewResolution, onConfirm = {}, onDismiss = {})
    }
}
