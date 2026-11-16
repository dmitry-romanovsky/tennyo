package app.tennyo.mobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import app.tennyo.contract.ChatAuthMethod
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.HostEditor
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoStatusBanner
import app.tennyo.mobile.design.TennyoStatusTone
import app.tennyo.mobile.design.layout.PanePadding
import app.tennyo.mobile.design.layout.UnavailablePane
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.editorTextStyle
import app.tennyo.mobile.feature.chat.generated.resources.Res
import app.tennyo.mobile.feature.chat.generated.resources.chat_failure_exited
import app.tennyo.mobile.feature.chat.generated.resources.chat_failure_sign_in
import app.tennyo.mobile.feature.chat.generated.resources.chat_failure_start
import app.tennyo.mobile.feature.chat.generated.resources.chat_failure_turn
import app.tennyo.mobile.feature.chat.generated.resources.chat_sign_in_body
import app.tennyo.mobile.feature.chat.generated.resources.chat_sign_in_done
import app.tennyo.mobile.feature.chat.generated.resources.chat_sign_in_run
import app.tennyo.mobile.feature.chat.generated.resources.chat_sign_in_title
import app.tennyo.mobile.feature.chat.generated.resources.chat_unavailable_body
import app.tennyo.mobile.feature.chat.generated.resources.chat_unavailable_body_devin
import app.tennyo.mobile.feature.chat.generated.resources.chat_unavailable_body_jetbrains
import app.tennyo.mobile.feature.chat.generated.resources.chat_unavailable_body_vscode
import app.tennyo.mobile.feature.chat.generated.resources.chat_unavailable_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Chat destination: the agent picker, and sign-in when the agent needs it. The conversation itself is
 * [ChatConversationScreen].
 *
 * A host that does not serve [app.tennyo.contract.Capability.CHAT] gets the unavailable state instead: the destination
 * is always offered, and says why it is empty.
 */
@Composable
fun ChatPane(
    component: ChatPaneComponent,
    panePadding: PanePadding,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val onIntent = component::onIntent

    when {
        !state.available -> UnavailablePane(
            icon = TennyoIcons.Forum,
            title = stringResource(Res.string.chat_unavailable_title),
            reason = stringResource(state.editor.unavailableBody()),
            modifier = modifier,
        )
        state.host.authMethods.isNotEmpty() ->
            SignIn(
                state,
                onIntent,
                modifier.fillMaxSize().padding(top = panePadding.contentPadding.calculateTopPadding()),
            )
        // The conversation is a screen of its own over the workspace; the list stays underneath it.
        else -> Column(modifier = modifier.fillMaxSize()) {
            AgentPicker(
                state = state,
                onIntent = onIntent,
                contentPadding = pickerPadding(panePadding),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            // Below the list rather than its last row: a failed start must not scroll out of sight.
            state.shownFailure?.let { failure ->
                TennyoStatusBanner(
                    title = stringResource(failure.kind.title),
                    message = failure.detail.orEmpty(),
                    tone = TennyoStatusTone.Failed,
                    onDismiss = { onIntent(ChatIntent.DismissFailure) },
                    modifier = Modifier
                        .padding(horizontal = Spacing.large)
                        .padding(bottom = panePadding.bottomSafeInset + Spacing.medium),
                )
            }
        }
    }
}

/**
 * The agent needs signing in, which happens on the computer: an agent method opens its own sign-in there, a terminal
 * method is a command to run in a terminal there. Either way the phone only starts it and waits.
 */
@Composable
private fun SignIn(state: ChatState, onIntent: (ChatIntent) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.padding(Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        item {
            Text(stringResource(Res.string.chat_sign_in_title), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Text(
                stringResource(Res.string.chat_sign_in_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.host.authMethods, key = { it.id }) { method -> SignInMethod(method, state, onIntent) }
    }
}

@Composable
private fun SignInMethod(
    method: ChatAuthMethod,
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val command = method.terminalCommand
    if (command == null) {
        TennyoButton(
            label = method.name,
            onClick = { onIntent(ChatIntent.Authenticate(method.id)) },
            loading = state.starting,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(stringResource(Res.string.chat_sign_in_run, method.name), style = MaterialTheme.typography.bodyMedium)
        Text(command, style = editorTextStyle(), modifier = Modifier.fillMaxWidth())
        TennyoButton(
            label = stringResource(Res.string.chat_sign_in_done),
            onClick = { state.agentId?.let { onIntent(ChatIntent.StartAgent(it)) } },
            style = TennyoButtonStyle.Secondary,
            loading = state.starting,
        )
    }
}

internal val ChatFailureKind.title: StringResource
    get() = when (this) {
        ChatFailureKind.START_FAILED -> Res.string.chat_failure_start
        ChatFailureKind.SIGN_IN_REQUIRED -> Res.string.chat_failure_sign_in
        ChatFailureKind.AGENT_EXITED -> Res.string.chat_failure_exited
        ChatFailureKind.TURN_FAILED -> Res.string.chat_failure_turn
    }

/** The pane's own insets for a list: the top and sides it is given, the bottom left to the list's own scroll. */
@Composable
private fun pickerPadding(panePadding: PanePadding): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = panePadding.contentPadding.calculateStartPadding(direction) + Spacing.large,
        top = panePadding.contentPadding.calculateTopPadding() + Spacing.medium,
        end = panePadding.contentPadding.calculateEndPadding(direction) + Spacing.large,
        bottom = panePadding.bottomSafeInset + Spacing.large,
    )
}

/** Where to add an agent depends on the editor the host runs in; an unknown editor gets the general text. */
private fun HostEditor?.unavailableBody(): StringResource = when (this) {
    HostEditor.JETBRAINS -> Res.string.chat_unavailable_body_jetbrains
    HostEditor.VS_CODE, HostEditor.CURSOR -> Res.string.chat_unavailable_body_vscode
    HostEditor.DEVIN_DESKTOP -> Res.string.chat_unavailable_body_devin
    null -> Res.string.chat_unavailable_body
}
