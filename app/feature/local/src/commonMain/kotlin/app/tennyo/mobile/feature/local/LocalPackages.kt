package app.tennyo.mobile.feature.local

/** Why the install dialog was opened: the workspace's `requirements.txt`, or a module a run could not import. */
sealed interface PackageSource {
    data class RequirementsFile(val requirements: List<Requirement>) : PackageSource

    /** The user names the distribution that provides [module]; the app never guesses it. */
    data class MissingModule(val module: String) : PackageSource
}

/**
 * Installing packages for a workspace, step by step. Each step carries the [id] of the install it belongs to, so an
 * answer from one the user already dismissed is dropped. [thenRun] is the script to run once the packages are in.
 */
sealed interface PackageInstall {
    val id: Long
    val thenRun: String?
    val source: PackageSource

    /** Whether the run sheet shows this step; the two consents are dialogs instead. */
    val showsInSheet: Boolean get() = this is Resolving || this is Installing || this is Failed

    /** The first consent: nothing has touched the network yet. */
    data class Asking(
        override val id: Long,
        override val thenRun: String?,
        override val source: PackageSource,
    ) : PackageInstall

    data class Resolving(
        override val id: Long,
        override val thenRun: String?,
        override val source: PackageSource,
    ) : PackageInstall

    /** The second consent, over everything that would be installed. [record] is what is kept once it is. */
    data class Reviewing(
        override val id: Long,
        override val thenRun: String?,
        override val source: PackageSource,
        val resolution: PackageResolution.Resolved,
        val record: PackagesRecord,
    ) : PackageInstall

    data class Installing(
        override val id: Long,
        override val thenRun: String?,
        override val source: PackageSource,
    ) : PackageInstall

    /** [detail] is the distribution or requirement the failure is about. */
    data class Failed(
        override val id: Long,
        override val thenRun: String?,
        override val source: PackageSource,
        val failure: PackageFailure,
        val detail: String,
    ) : PackageInstall
}

/** The install-related messages of [LocalWorkspaceMsg]; [reducePackages] applies them. */
internal sealed interface LocalPackagesMsg : LocalWorkspaceMsg {
    data class InstallAsked(val id: Long, val source: PackageSource, val thenRun: String?) : LocalPackagesMsg
    data class InstallConfirmed(val id: Long) : LocalPackagesMsg
    data class Resolved(val id: Long, val resolution: PackageResolution, val record: PackagesRecord) : LocalPackagesMsg
    data class InstallReviewed(val id: Long) : LocalPackagesMsg
    data class InstallFinished(val id: Long, val failure: PackageResolution.Failed?) : LocalPackagesMsg
    data object InstallDismissed : LocalPackagesMsg

    /** The outcome of reading `requirements.txt` before a run: the rejected line, or `null` when it is fine. */
    data class RequirementsChecked(val rejectedLine: Int?) : LocalPackagesMsg
}

internal fun LocalWorkspaceState.reducePackages(msg: LocalPackagesMsg): LocalWorkspaceState {
    val install = packages
    return when (msg) {
        // One install at a time: a second ask while a step is showing is dropped.
        is LocalPackagesMsg.InstallAsked ->
            if (install == null) copy(packages = PackageInstall.Asking(msg.id, msg.thenRun, msg.source)) else this
        is LocalPackagesMsg.InstallConfirmed ->
            if (install is PackageInstall.Asking && install.id == msg.id) {
                copy(packages = PackageInstall.Resolving(install.id, install.thenRun, install.source))
            } else {
                this
            }

        is LocalPackagesMsg.Resolved ->
            if (install is PackageInstall.Resolving && install.id == msg.id) {
                copy(packages = install.resolved(msg.resolution, msg.record))
            } else {
                this
            }

        is LocalPackagesMsg.InstallReviewed ->
            if (install is PackageInstall.Reviewing && install.id == msg.id) {
                copy(packages = PackageInstall.Installing(install.id, install.thenRun, install.source))
            } else {
                this
            }

        is LocalPackagesMsg.InstallFinished ->
            if (install is PackageInstall.Installing && install.id == msg.id) {
                copy(
                    packages = msg.failure?.let {
                        PackageInstall.Failed(install.id, install.thenRun, install.source, it.failure, it.detail)
                    },
                )
            } else {
                this
            }

        LocalPackagesMsg.InstallDismissed -> copy(
            packages = null,
            declinedRequirements = (install?.source as? PackageSource.RequirementsFile)
                ?.takeIf { install is PackageInstall.Asking || install is PackageInstall.Failed }
                ?.requirements?.map(Requirement::line) ?: declinedRequirements,
        )

        is LocalPackagesMsg.RequirementsChecked -> copy(requirementsRejectedLine = msg.rejectedLine)
    }
}

private fun PackageInstall.Resolving.resolved(resolution: PackageResolution, record: PackagesRecord) =
    when (resolution) {
        is PackageResolution.Resolved -> PackageInstall.Reviewing(id, thenRun, source, resolution, record)
        is PackageResolution.Failed ->
            PackageInstall.Failed(id, thenRun, source, resolution.failure, resolution.detail)
    }
