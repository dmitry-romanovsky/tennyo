package app.tennyo.mobile.feature.git

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkPush
import app.tennyo.mobile.link.pane.PaneLink
import app.tennyo.mobile.link.pane.SyncPass
import app.tennyo.mobile.link.pane.WorkspacePane
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext

/**
 * Keeps a Git [pane] reading from [IdeGitSource] in step with its link session: status loads with each snapshot and
 * sync pass while shown, the host's invalidations refetch what is on screen, and losing the Git capability clears the
 * pane.
 */
class LinkGitPane(
    private val pane: RealGitPaneComponent,
    private val link: PaneLink,
    private val isShown: () -> Boolean,
    lifecycle: Lifecycle,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : WorkspacePane {
    private val available: Boolean
        get() = link.ticket.value != null && Capability.GIT in link.project.value.capabilities

    init {
        val scope = CoroutineScope(SupervisorJob() + mainContext)
        lifecycle.doOnDestroy { scope.cancel() }
        link.project
            .map { Capability.GIT in it.capabilities }
            .distinctUntilChanged()
            .drop(1)
            .filter { gitAvailable -> !gitAvailable }
            .onEach { pane.reset() }
            .launchIn(scope)
    }

    override suspend fun loadSnapshot(shown: Boolean) {
        if (shown && available) pane.refresh()
    }

    override suspend fun sync(pass: SyncPass) {
        if (pass.shown && pass.metadata && available) pane.refresh()
    }

    override fun apply(push: LinkPush) {
        if (push is LinkPush.GitInvalidated) pane.invalidate(push.revision, shown = isShown())
    }

    override fun onShown() {
        if (available) pane.refresh()
    }

    override fun onBack(): Boolean = pane.onBack()
}
