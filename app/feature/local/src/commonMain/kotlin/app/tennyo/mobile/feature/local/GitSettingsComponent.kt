package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitIdentity
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The Git page of Settings: who commits on this device, and the hosts that have saved credentials. */
interface GitSettingsComponent {
    val state: StateFlow<GitSettingsState>

    fun onIntent(intent: GitSettingsIntent)
}

data class GitSettingsState(
    val name: String = "",
    val email: String = "",
    val savedIdentity: GitIdentity? = null,
    val credentialHosts: List<String> = emptyList(),
) {
    val canSaveIdentity: Boolean
        get() = name.isNotBlank() && email.isNotBlank() && GitIdentity(name.trim(), email.trim()) != savedIdentity
}

sealed interface GitSettingsIntent {
    data class ChangeName(val name: String) : GitSettingsIntent
    data class ChangeEmail(val email: String) : GitSettingsIntent
    data object SaveIdentity : GitSettingsIntent
    data class DeleteCredentials(val host: String) : GitSettingsIntent
}

internal sealed interface GitSettingsMsg {
    data class NameChanged(val name: String) : GitSettingsMsg
    data class EmailChanged(val email: String) : GitSettingsMsg
    data class IdentityLoaded(val identity: GitIdentity?) : GitSettingsMsg
    data class HostsLoaded(val hosts: List<String>) : GitSettingsMsg
}

internal fun reduce(state: GitSettingsState, msg: GitSettingsMsg): GitSettingsState = when (msg) {
    is GitSettingsMsg.NameChanged -> state.copy(name = msg.name)
    is GitSettingsMsg.EmailChanged -> state.copy(email = msg.email)
    is GitSettingsMsg.IdentityLoaded -> state.copy(
        savedIdentity = msg.identity,
        name = msg.identity?.name ?: state.name,
        email = msg.identity?.email ?: state.email,
    )

    is GitSettingsMsg.HostsLoaded -> state.copy(credentialHosts = msg.hosts)
}

class RealGitSettingsComponent(
    componentContext: ComponentContext,
    private val identity: GitIdentityPreferences,
    private val credentials: GitCredentialStore,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : GitSettingsComponent, ComponentContext by componentContext {
    private val feature = FeatureStore<GitSettingsState, GitSettingsMsg, Nothing>(
        GitSettingsState(),
        lifecycle,
        ::reduce,
        mainContext,
    )

    override val state: StateFlow<GitSettingsState> = feature.state

    init {
        lifecycle.doOnCreate {
            identity.identity.onEach { feature.dispatch(GitSettingsMsg.IdentityLoaded(it)) }.launchIn(feature.scope)
            credentials.hosts.onEach { feature.dispatch(GitSettingsMsg.HostsLoaded(it)) }.launchIn(feature.scope)
        }
    }

    override fun onIntent(intent: GitSettingsIntent) {
        when (intent) {
            is GitSettingsIntent.ChangeName -> feature.dispatch(GitSettingsMsg.NameChanged(intent.name))
            is GitSettingsIntent.ChangeEmail -> feature.dispatch(GitSettingsMsg.EmailChanged(intent.email))
            GitSettingsIntent.SaveIdentity -> state.value.takeIf { it.canSaveIdentity }?.let {
                identity.save(GitIdentity(it.name, it.email))
            }

            is GitSettingsIntent.DeleteCredentials -> feature.scope.launch {
                runCatchingCancellable { credentials.delete(intent.host) }
                    .onFailure { Logger.w(it) { "Git credentials for ${intent.host} could not be deleted" } }
            }
        }
    }
}
