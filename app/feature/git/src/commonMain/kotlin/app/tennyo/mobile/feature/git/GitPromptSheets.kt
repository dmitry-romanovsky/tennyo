package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_branch_name
import app.tennyo.mobile.feature.git.generated.resources.git_cancel
import app.tennyo.mobile.feature.git.generated.resources.git_create_branch
import app.tennyo.mobile.feature.git.generated.resources.git_credentials_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_credentials_secret
import app.tennyo.mobile.feature.git.generated.resources.git_credentials_sign_in
import app.tennyo.mobile.feature.git.generated.resources.git_credentials_title
import app.tennyo.mobile.feature.git.generated.resources.git_credentials_username
import app.tennyo.mobile.feature.git.generated.resources.git_delete_branch
import app.tennyo.mobile.feature.git.generated.resources.git_delete_branch_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_delete_branch_title
import app.tennyo.mobile.feature.git.generated.resources.git_diverged_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_diverged_title
import app.tennyo.mobile.feature.git.generated.resources.git_identity_email
import app.tennyo.mobile.feature.git.generated.resources.git_identity_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_identity_name
import app.tennyo.mobile.feature.git.generated.resources.git_identity_save
import app.tennyo.mobile.feature.git.generated.resources.git_identity_save_only
import app.tennyo.mobile.feature.git.generated.resources.git_identity_title
import app.tennyo.mobile.feature.git.generated.resources.git_merge
import app.tennyo.mobile.feature.git.generated.resources.git_new_branch
import app.tennyo.mobile.feature.git.generated.resources.git_remote_edit
import app.tennyo.mobile.feature.git.generated.resources.git_remote_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_remote_name
import app.tennyo.mobile.feature.git.generated.resources.git_remote_new
import app.tennyo.mobile.feature.git.generated.resources.git_remote_remove
import app.tennyo.mobile.feature.git.generated.resources.git_remote_save
import app.tennyo.mobile.feature.git.generated.resources.git_remote_url
import app.tennyo.mobile.feature.git.generated.resources.git_reset_head
import app.tennyo.mobile.feature.git.generated.resources.git_reset_head_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_reset_head_title
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_files
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_files_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_files_title
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_more_files
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_new_file_explanation
import app.tennyo.mobile.feature.git.generated.resources.git_rollback_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sheet for the open [GitPrompt]: credentials, the commit author, a diverged pull, a new branch name, a remote, or
 * the confirmation before work or a branch is discarded.
 */
@Composable
internal fun GitPromptSheet(
    prompt: GitPrompt,
    onIntent: (GitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = { onIntent(GitIntent.DismissPrompt) }
    GitModalBottomSheet(onDismissRequest = dismiss, modifier = modifier) {
        val title = when (prompt) {
            is GitPrompt.Credentials -> stringResource(Res.string.git_credentials_title, prompt.host)
            is GitPrompt.Identity -> stringResource(Res.string.git_identity_title)
            is GitPrompt.Diverged -> stringResource(Res.string.git_diverged_title)
            is GitPrompt.NewBranch -> stringResource(Res.string.git_new_branch)
            is GitPrompt.EditRemote ->
                stringResource(if (prompt.remote == null) Res.string.git_remote_new else Res.string.git_remote_edit)
            is GitPrompt.ConfirmDiscard -> when (val discard = prompt.discard) {
                is GitDiscard.Changes -> if (discard.changes.size == 1) {
                    stringResource(Res.string.git_rollback_title, discard.changes.single().fileName)
                } else {
                    val count = discard.changes.size
                    pluralStringResource(Res.plurals.git_rollback_files_title, count, count)
                }
                GitDiscard.AllTracked -> stringResource(Res.string.git_reset_head_title)
                is GitDiscard.Branch -> stringResource(Res.string.git_delete_branch_title, discard.name)
            }
        }
        TennyoBottomSheetHeader(
            title = title,
            actionLabel = stringResource(Res.string.git_cancel),
            onAction = dismiss,
            modifier = Modifier.fillMaxWidth(),
        )
        val contentModifier = Modifier.fillMaxWidth().padding(Spacing.medium)
        when (prompt) {
            is GitPrompt.Credentials -> GitCredentialsForm(onIntent, contentModifier)
            is GitPrompt.Identity -> GitIdentityForm(prompt, onIntent, contentModifier)
            is GitPrompt.Diverged -> GitDivergedChoice(onIntent, contentModifier)
            is GitPrompt.NewBranch -> GitNewBranchForm(prompt.repositoryId, onIntent, contentModifier)
            is GitPrompt.EditRemote -> GitRemoteForm(prompt.remote, onIntent, contentModifier)
            is GitPrompt.ConfirmDiscard -> GitDiscardConfirmation(prompt.discard, onIntent, contentModifier)
        }
        Spacer(Modifier.height(safeDrawingBottomInset()))
    }
}

@Composable
private fun GitCredentialsForm(onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    // The secret stays out of saved instance state; it lives only as long as the sheet.
    var username by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    GitPromptForm(
        explanation = stringResource(Res.string.git_credentials_explanation),
        actionLabel = stringResource(Res.string.git_credentials_sign_in),
        actionEnabled = username.isNotBlank() && secret.isNotEmpty(),
        onAction = { onIntent(GitIntent.SubmitCredentials(username.trim(), secret)) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.git_credentials_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(stringResource(Res.string.git_credentials_secret)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The commit author, prefilled with the current one; saving also runs the commit that asked for it, if any. */
@Composable
private fun GitIdentityForm(prompt: GitPrompt.Identity, onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(prompt.current?.name.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(prompt.current?.email.orEmpty()) }
    GitPromptForm(
        explanation = stringResource(Res.string.git_identity_explanation),
        actionLabel = stringResource(
            if (prompt.retry == null) Res.string.git_identity_save_only else Res.string.git_identity_save,
        ),
        actionEnabled = name.isNotBlank() && email.isNotBlank(),
        onAction = { onIntent(GitIntent.SubmitIdentity(name, email)) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.git_identity_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(Res.string.git_identity_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GitDivergedChoice(onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    GitPromptForm(
        explanation = stringResource(Res.string.git_diverged_explanation),
        actionLabel = stringResource(Res.string.git_merge),
        actionEnabled = true,
        onAction = { onIntent(GitIntent.ConfirmMerge) },
        modifier = modifier,
    ) {}
}

/** Says exactly what will be lost before the one destructive button. */
@Composable
private fun GitDiscardConfirmation(discard: GitDiscard, onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    val changes = (discard as? GitDiscard.Changes)?.changes.orEmpty()
    val explanation = when {
        discard == GitDiscard.AllTracked -> stringResource(Res.string.git_reset_head_explanation)
        discard is GitDiscard.Branch -> stringResource(Res.string.git_delete_branch_explanation)
        changes.size > 1 -> stringResource(Res.string.git_rollback_files_explanation)
        else -> changes.first().let { change ->
            stringResource(
                if (change.isNewFile) {
                    Res.string.git_rollback_new_file_explanation
                } else {
                    Res.string.git_rollback_explanation
                },
                change.path,
            )
        }
    }
    GitPromptForm(
        explanation = explanation,
        actionLabel = when (discard) {
            is GitDiscard.Changes -> pluralStringResource(Res.plurals.git_rollback_files, changes.size, changes.size)
            GitDiscard.AllTracked -> stringResource(Res.string.git_reset_head)
            is GitDiscard.Branch -> stringResource(Res.string.git_delete_branch)
        },
        actionEnabled = true,
        onAction = { onIntent(GitIntent.ConfirmDiscard) },
        actionStyle = TennyoButtonStyle.Danger,
        modifier = modifier,
    ) {
        // Several files: name each one, so nothing is discarded that the user did not see listed.
        if (changes.size > 1) {
            Column(modifier = Modifier.padding(vertical = Spacing.small)) {
                changes.take(DISCARD_LISTED_FILES).forEach { change ->
                    Text(
                        text = change.path,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (change.isNewFile) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (changes.size > DISCARD_LISTED_FILES) {
                    Text(
                        text = stringResource(Res.string.git_rollback_more_files, changes.size - DISCARD_LISTED_FILES),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val GitChange.isNewFile: Boolean
    get() = kind == GitChangeKind.UNTRACKED || kind == GitChangeKind.ADDED

private val GitChange.path: String
    get() = afterPath ?: beforePath ?: fileName

/** How many files the confirmation names before it only counts the rest. */
private const val DISCARD_LISTED_FILES = 8

@Composable
private fun GitNewBranchForm(repositoryId: String, onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    GitPromptForm(
        explanation = null,
        actionLabel = stringResource(Res.string.git_create_branch),
        actionEnabled = name.isNotBlank(),
        onAction = { onIntent(GitIntent.CreateBranch(repositoryId, name)) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.git_branch_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A remote's name and URL; an existing remote can also be removed from here. */
@Composable
private fun GitRemoteForm(remote: GitRemote?, onIntent: (GitIntent) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(remote?.name ?: "origin") }
    var url by rememberSaveable { mutableStateOf(remote?.url.orEmpty()) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        GitPromptForm(
            explanation = stringResource(Res.string.git_remote_explanation),
            actionLabel = stringResource(Res.string.git_remote_save),
            actionEnabled = name.isNotBlank() && name.trim().none(Char::isWhitespace) && url.isNotBlank(),
            onAction = { onIntent(GitIntent.SaveRemote(name, url)) },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.git_remote_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(Res.string.git_remote_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (remote != null) {
            TennyoButton(
                label = stringResource(Res.string.git_remote_remove),
                onClick = { onIntent(GitIntent.RemoveRemote) },
                style = TennyoButtonStyle.Text,
                size = TennyoButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GitPromptForm(
    explanation: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionStyle: TennyoButtonStyle = TennyoButtonStyle.Primary,
    fields: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        explanation?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        fields()
        TennyoButton(
            label = actionLabel,
            onClick = onAction,
            enabled = actionEnabled,
            style = actionStyle,
            size = TennyoButtonSize.PrimaryAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Roll back a file", widthDp = 420, heightDp = 400, showBackground = true)
@Composable
private fun GitRollbackSheetPreview() {
    GitScreenPreview(
        transform = { state ->
            val change = GitChange(
                id = "a",
                kind = GitChangeKind.MODIFIED,
                afterPath = "src/App.kt",
                fileName = "App.kt",
                parentPath = "src",
            )
            val discard = GitDiscard.Changes(listOf(change), expectedRevision = 1)
            state.copy(prompt = GitPrompt.ConfirmDiscard("repo", discard))
        },
    ) { git, repository, onIntent, modifier ->
        GitSheets(git, repository, onIntent, modifier)
    }
}

@Preview(name = "Reset to HEAD", widthDp = 420, heightDp = 400, showBackground = true)
@Composable
private fun GitResetHeadSheetPreview() {
    GitScreenPreview(
        transform = { it.copy(prompt = GitPrompt.ConfirmDiscard("repo", GitDiscard.AllTracked)) },
    ) { git, repository, onIntent, modifier ->
        GitSheets(git, repository, onIntent, modifier)
    }
}
