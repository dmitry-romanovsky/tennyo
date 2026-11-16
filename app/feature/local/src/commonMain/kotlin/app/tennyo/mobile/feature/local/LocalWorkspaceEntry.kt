package app.tennyo.mobile.feature.local

import com.arkivanov.decompose.ComponentContext

/**
 * The on-device workspace feature as the app shell sees it. A platform that binds an implementation gets the local
 * section on Home, the three workspace screens and the Git settings page; a platform that binds none shows the
 * link flow only.
 */
interface LocalWorkspaceEntry {
    fun home(
        componentContext: ComponentContext,
        onOpenWorkspace: (slug: String) -> Unit,
        onOpenCreate: () -> Unit,
        onOpenClone: () -> Unit,
    ): LocalHomeComponent

    fun workspace(componentContext: ComponentContext, slug: String, onBack: () -> Unit): LocalWorkspaceComponent

    fun create(
        componentContext: ComponentContext,
        onBack: () -> Unit,
        onCreated: (slug: String) -> Unit,
    ): CreateWorkspaceComponent

    fun clone(
        componentContext: ComponentContext,
        onBack: () -> Unit,
        onCloned: (slug: String) -> Unit,
    ): CloneWorkspaceComponent

    fun gitSettings(componentContext: ComponentContext): GitSettingsComponent
}

/** The feature over its storage, Git and settings collaborators; platforms supply those. */
class RealLocalWorkspaceEntry(
    private val store: LocalWorkspaceStore,
    private val files: WorkspaceFiles,
    private val repositories: LocalGitRepositories,
    private val transfers: GitTransferScheduler,
    private val identity: GitIdentityPreferences,
    private val credentials: GitCredentialStore,
    private val python: PythonRunner?,
) : LocalWorkspaceEntry {
    override fun home(
        componentContext: ComponentContext,
        onOpenWorkspace: (slug: String) -> Unit,
        onOpenCreate: () -> Unit,
        onOpenClone: () -> Unit,
    ): LocalHomeComponent = RealLocalHomeComponent(componentContext, store, onOpenWorkspace, onOpenCreate, onOpenClone)

    override fun workspace(componentContext: ComponentContext, slug: String, onBack: () -> Unit) =
        RealLocalWorkspaceComponent(componentContext, slug, store, files, repositories.source(slug), python, onBack)

    override fun create(componentContext: ComponentContext, onBack: () -> Unit, onCreated: (slug: String) -> Unit) =
        RealCreateWorkspaceComponent(componentContext, store, files, repositories, onBack, onCreated)

    override fun clone(componentContext: ComponentContext, onBack: () -> Unit, onCloned: (slug: String) -> Unit) =
        RealCloneWorkspaceComponent(componentContext, store, transfers, onBack, onCloned)

    override fun gitSettings(componentContext: ComponentContext) =
        RealGitSettingsComponent(componentContext, identity, credentials)
}
