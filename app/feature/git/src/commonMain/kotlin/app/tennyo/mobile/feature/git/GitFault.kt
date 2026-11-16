package app.tennyo.mobile.feature.git

import androidx.compose.runtime.Composable
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_failure_auth_required
import app.tennyo.mobile.feature.git.generated.resources.git_failure_busy
import app.tennyo.mobile.feature.git.generated.resources.git_failure_cancelled
import app.tennyo.mobile.feature.git.generated.resources.git_failure_conflicts
import app.tennyo.mobile.feature.git.generated.resources.git_failure_conflicts_unlisted
import app.tennyo.mobile.feature.git.generated.resources.git_failure_identity_missing
import app.tennyo.mobile.feature.git.generated.resources.git_failure_invalid_cursor
import app.tennyo.mobile.feature.git.generated.resources.git_failure_missing_upstream
import app.tennyo.mobile.feature.git.generated.resources.git_failure_network
import app.tennyo.mobile.feature.git.generated.resources.git_failure_non_fast_forward
import app.tennyo.mobile.feature.git.generated.resources.git_failure_not_a_repository
import app.tennyo.mobile.feature.git.generated.resources.git_failure_platform
import app.tennyo.mobile.feature.git.generated.resources.git_failure_rejected
import app.tennyo.mobile.feature.git.generated.resources.git_failure_stale_target
import app.tennyo.mobile.feature.git.generated.resources.git_failure_timed_out
import app.tennyo.mobile.feature.git.generated.resources.git_failure_unexpected
import app.tennyo.mobile.feature.git.generated.resources.git_failure_unsupported_target
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_auth_failed_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_busy_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_cancelled_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_completed_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_missing_upstream_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_platform_failure_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_rejected_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_stale_message
import app.tennyo.mobile.feature.git.generated.resources.git_outcome_unsupported_message

/** Why a Git read or operation failed, as the source reports it. The UI words it; logic branches on the type. */
sealed class GitFault : Exception() {
    data object NotARepository : GitFault()

    /** [host] wants credentials the source does not have. */
    data class AuthRequired(val host: String) : GitFault()

    /** A commit needs an author name and email that are not configured. */
    data object IdentityMissing : GitFault()

    /** The remote has commits the push would overwrite. */
    data object NonFastForward : GitFault()

    data class Conflicts(val paths: List<String>) : GitFault()

    data object Network : GitFault()

    data class Rejected(val reason: GitRejection) : GitFault()
}

/** Why the repository's owner refused a request. */
enum class GitRejection {
    STALE_TARGET,
    UNSUPPORTED_TARGET,
    INVALID_CURSOR,
    BUSY,
    MISSING_UPSTREAM,
    REJECTED,
    CANCELLED,
    PLATFORM_FAILURE,

    /** The source did not answer within [GIT_READ_TIMEOUT]. */
    TIMED_OUT,
    UNEXPECTED,
}

/** A failure that is not a [GitFault] is unexpected; there is nothing more specific to tell the user. */
internal fun Throwable.toGitFault(): GitFault = this as? GitFault ?: GitFault.Rejected(GitRejection.UNEXPECTED)

/**
 * Whether the mutation that failed with this fault certainly changed nothing. A lost answer or a failure the source
 * could not explain leaves the outcome unknown until the next status shows it.
 */
internal val GitFault.leavesRepositoryUnchanged: Boolean
    get() = this != GitFault.Network && this != GitFault.Rejected(GitRejection.UNEXPECTED)

val GitFault.text: UiText
    get() = when (this) {
        GitFault.NotARepository -> UiText.Resource(Res.string.git_failure_not_a_repository)
        is GitFault.AuthRequired -> UiText.Resource(Res.string.git_failure_auth_required, host)
        GitFault.IdentityMissing -> UiText.Resource(Res.string.git_failure_identity_missing)
        GitFault.NonFastForward -> UiText.Resource(Res.string.git_failure_non_fast_forward)
        is GitFault.Conflicts -> if (paths.isEmpty()) {
            UiText.Resource(Res.string.git_failure_conflicts_unlisted)
        } else {
            UiText.Resource(Res.string.git_failure_conflicts, paths.size)
        }
        GitFault.Network -> UiText.Resource(Res.string.git_failure_network)
        is GitFault.Rejected -> UiText.Resource(
            when (reason) {
                GitRejection.STALE_TARGET -> Res.string.git_failure_stale_target
                GitRejection.UNSUPPORTED_TARGET -> Res.string.git_failure_unsupported_target
                GitRejection.INVALID_CURSOR -> Res.string.git_failure_invalid_cursor
                GitRejection.BUSY -> Res.string.git_failure_busy
                GitRejection.MISSING_UPSTREAM -> Res.string.git_failure_missing_upstream
                GitRejection.REJECTED -> Res.string.git_failure_rejected
                GitRejection.CANCELLED -> Res.string.git_failure_cancelled
                GitRejection.PLATFORM_FAILURE -> Res.string.git_failure_platform
                GitRejection.TIMED_OUT -> Res.string.git_failure_timed_out
                GitRejection.UNEXPECTED -> Res.string.git_failure_unexpected
            },
        )
    }

@Composable
internal fun GitFault.displayMessage(): String = text.resolve()

internal val GitOutcome.message: UiText
    get() = UiText.Resource(
        when (this) {
            GitOutcome.SUCCESS -> Res.string.git_outcome_completed_message
            GitOutcome.CANCELLED -> Res.string.git_outcome_cancelled_message
            GitOutcome.BUSY -> Res.string.git_outcome_busy_message
            GitOutcome.REJECTED -> Res.string.git_outcome_rejected_message
            GitOutcome.PLATFORM_FAILURE -> Res.string.git_outcome_platform_failure_message
            GitOutcome.STALE_TARGET -> Res.string.git_outcome_stale_message
            GitOutcome.UNSUPPORTED -> Res.string.git_outcome_unsupported_message
            GitOutcome.MISSING_UPSTREAM -> Res.string.git_outcome_missing_upstream_message
            GitOutcome.AUTH_FAILED -> Res.string.git_outcome_auth_failed_message
        },
    )
