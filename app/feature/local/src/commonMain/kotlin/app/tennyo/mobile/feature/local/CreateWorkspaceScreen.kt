package app.tennyo.mobile.feature.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoListRow
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.layout.FabClearanceSpacer
import app.tennyo.mobile.design.layout.PlatformTopAppBar
import app.tennyo.mobile.design.layout.PlatformTopAppBarSize
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_create_action
import app.tennyo.mobile.feature.local.generated.resources.local_create_failed
import app.tennyo.mobile.feature.local.generated.resources.local_create_git_repository
import app.tennyo.mobile.feature.local.generated.resources.local_create_readme
import app.tennyo.mobile.feature.local.generated.resources.local_create_title
import app.tennyo.mobile.feature.local.generated.resources.local_kind_empty
import app.tennyo.mobile.feature.local.generated.resources.local_kind_empty_body
import app.tennyo.mobile.feature.local.generated.resources.local_kind_python_package
import app.tennyo.mobile.feature.local.generated.resources.local_kind_python_package_body
import app.tennyo.mobile.feature.local.generated.resources.local_kind_python_script
import app.tennyo.mobile.feature.local.generated.resources.local_kind_python_script_body
import app.tennyo.mobile.feature.local.generated.resources.local_kind_title
import app.tennyo.mobile.feature.local.generated.resources.local_workspace_name
import app.tennyo.mobile.feature.local.generated.resources.local_workspace_storage_note
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWorkspaceScreen(component: CreateWorkspaceComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        // Edge to edge, nothing resizes for the keyboard: the form and its button lift above it here.
        modifier = modifier.imePadding().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PlatformTopAppBar(
                title = stringResource(Res.string.local_create_title),
                onBack = component::onBack,
                actions = {},
                modifier = Modifier.fillMaxWidth(),
                size = PlatformTopAppBarSize.Large,
                bottomContent = null,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.canCreate) component.onIntent(CreateWorkspaceIntent.Create) },
            ) {
                Icon(imageVector = TennyoIcons.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(Spacing.small))
                Text(text = stringResource(Res.string.local_create_action))
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CreateWorkspaceForm(
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
private fun CreateWorkspaceForm(
    state: CreateWorkspaceState,
    onIntent: (CreateWorkspaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val template = state.template
    val changeTemplate = { updated: WorkspaceTemplate -> onIntent(CreateWorkspaceIntent.ChangeTemplate(updated)) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        state.failure?.let { failure ->
            TennyoStatusBanner(
                title = stringResource(Res.string.local_create_failed),
                message = failure.resolve(),
                tone = TennyoStatusTone.Failed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.name,
            onValueChange = { onIntent(CreateWorkspaceIntent.ChangeName(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.local_workspace_name)) },
            isError = state.name.isBlank(),
            singleLine = true,
        )
        Text(
            text = stringResource(Res.string.local_workspace_storage_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.small))
        Text(stringResource(Res.string.local_kind_title), style = MaterialTheme.typography.titleSmall)
        // Python leads: it is the kind of project that also runs here, not only on a computer.
        WorkspaceKind.entries.forEach { kind ->
            key(kind) {
                TennyoListRow(
                    title = stringResource(kind.title),
                    subtitle = stringResource(kind.description),
                    onClick = { changeTemplate(template.copy(kind = kind)) },
                    selected = template.kind == kind,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        Icon(
                            imageVector = if (kind == WorkspaceKind.EMPTY) TennyoIcons.Folder else TennyoIcons.Code,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(Spacing.small))
        CreateOptionRow(
            text = stringResource(Res.string.local_create_git_repository),
            checked = template.createGitRepository,
            onCheckedChange = { changeTemplate(template.copy(createGitRepository = it)) },
        )
        CreateOptionRow(
            text = stringResource(Res.string.local_create_readme),
            checked = template.addReadme,
            onCheckedChange = { changeTemplate(template.copy(addReadme = it)) },
        )
        FabClearanceSpacer()
    }
}

private val WorkspaceKind.title: StringResource
    get() = when (this) {
        WorkspaceKind.PYTHON_SCRIPT -> Res.string.local_kind_python_script
        WorkspaceKind.PYTHON_PACKAGE -> Res.string.local_kind_python_package
        WorkspaceKind.EMPTY -> Res.string.local_kind_empty
    }

private val WorkspaceKind.description: StringResource
    get() = when (this) {
        WorkspaceKind.PYTHON_SCRIPT -> Res.string.local_kind_python_script_body
        WorkspaceKind.PYTHON_PACKAGE -> Res.string.local_kind_python_package_body
        WorkspaceKind.EMPTY -> Res.string.local_kind_empty_body
    }

@Composable
private fun CreateOptionRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.sm)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.padding(Spacing.small),
        )
        Text(text = text)
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun CreateWorkspaceFormPreview() {
    TennyoTheme {
        CreateWorkspaceForm(
            state = CreateWorkspaceState(template = WorkspaceTemplate(addReadme = true)),
            onIntent = {},
        )
    }
}
