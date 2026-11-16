package app.tennyo.mobile.feature.local

import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_fault_already_exists
import app.tennyo.mobile.feature.local.generated.resources.local_fault_not_found
import app.tennyo.mobile.feature.local.generated.resources.local_fault_path_rejected
import app.tennyo.mobile.feature.local.generated.resources.local_fault_unreadable
import app.tennyo.mobile.feature.local.generated.resources.local_fault_unwritable

/** Why a workspace file operation failed. The UI words it through [text]; logic branches on the type. */
sealed class WorkspaceFault : Exception() {
    abstract val path: String

    /** [path] resolves outside the workspace: an absolute path, `..`, or a symlink leaving the root. */
    data class PathRejected(override val path: String) : WorkspaceFault()

    data class NotFound(override val path: String) : WorkspaceFault()

    data class Unreadable(override val path: String) : WorkspaceFault()

    data class Unwritable(override val path: String) : WorkspaceFault()

    /** A new file or folder was asked for where something already is. */
    data class AlreadyExists(override val path: String) : WorkspaceFault()
}

val WorkspaceFault.text: UiText
    get() = UiText.Resource(
        when (this) {
            is WorkspaceFault.PathRejected -> Res.string.local_fault_path_rejected
            is WorkspaceFault.NotFound -> Res.string.local_fault_not_found
            is WorkspaceFault.Unreadable -> Res.string.local_fault_unreadable
            is WorkspaceFault.Unwritable -> Res.string.local_fault_unwritable
            is WorkspaceFault.AlreadyExists -> Res.string.local_fault_already_exists
        },
        path,
    )
