package app.tennyo.host.engine.panes

import app.tennyo.contract.GIT_MAX_COMMIT_MESSAGE_LENGTH
import app.tennyo.contract.GIT_MAX_SELECTED_CHANGES
import app.tennyo.contract.GitCommitDetailResponse
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitHistoryResponse
import app.tennyo.crypto.decodeBase64Url
import app.tennyo.crypto.sha256
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

sealed interface GitHistoryResult {
    data class Success(val response: GitHistoryResponse) : GitHistoryResult
    data object MissingRepository : GitHistoryResult
    data object PlatformFailure : GitHistoryResult
}

sealed interface GitCommitDetailResult {
    data class Success(val response: GitCommitDetailResponse) : GitCommitDetailResult
    data object MissingTarget : GitCommitDetailResult
    data object PlatformFailure : GitCommitDetailResult
}

/** An opaque, stable id: the lower-case hex SHA-256 of [parts] joined by NUL. */
fun gitStableId(vararg parts: String): String =
    sha256(parts.joinToString("\u0000").encodeToByteArray())
        .joinToString("") { byte -> (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }

fun gitRepositoryId(rootPath: String): String = gitStableId("repository", rootPath)

fun resolveCountRepositoryId(selectedRepositoryId: String?, repositoryIds: List<String>): String? =
    selectedRepositoryId?.takeIf(repositoryIds::contains) ?: repositoryIds.singleOrNull()

enum class GitCommitValidation { Valid, Stale, Unsupported }

fun validateCommitRequest(
    request: GitCommitRequest,
    revision: Long,
): GitCommitValidation = when {
    request.expectedRevision != revision -> GitCommitValidation.Stale
    request.changeIds.distinct().size != request.changeIds.size -> GitCommitValidation.Stale
    request.changeIds.isEmpty() -> GitCommitValidation.Unsupported
    request.changeIds.size > GIT_MAX_SELECTED_CHANGES -> GitCommitValidation.Unsupported
    request.message.isBlank() -> GitCommitValidation.Unsupported
    request.message.length > GIT_MAX_COMMIT_MESSAGE_LENGTH -> GitCommitValidation.Unsupported
    else -> GitCommitValidation.Valid
}

/** Lets one mutation per repository run at a time; a second one is answered by `onBusy` instead of waiting. */
class GitMutationGuard {
    private val monitor = HostMonitor()
    private val activeRepositories = mutableSetOf<String>()

    suspend fun <T> run(repositoryId: String, onBusy: () -> T, action: suspend () -> T): T {
        if (!monitor.guarded { activeRepositories.add(repositoryId) }) return onBusy()
        return try {
            action()
        } finally {
            monitor.guarded { activeRepositories.remove(repositoryId) }
        }
    }
}

fun gitHistoryCursor(repositoryId: String, revision: Long, offset: Int): String =
    "$repositoryId\u0000$revision\u0000$offset".encodeToByteArray().toBase64Url()

fun gitHistoryCursorOffset(repositoryId: String, revision: Long, cursor: String): Int? =
    runCatching {
        cursor.decodeBase64Url().decodeToString().split('\u0000', limit = HISTORY_CURSOR_PARTS)
    }.getOrNull()?.let { parts ->
        parts.takeIf { it.size == HISTORY_CURSOR_PARTS && it[0] == repositoryId && it[1].toLongOrNull() == revision }
            ?.get(2)
            ?.toIntOrNull()
            ?.takeIf { it in 0..MAX_HISTORY_OFFSET }
    }

/** A full SHA-1 or SHA-256 object id in lower-case hex. */
fun String.isGitObjectId(): Boolean =
    length in MIN_GIT_OBJECT_ID_LENGTH..MAX_GIT_OBJECT_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private const val MAX_HISTORY_OFFSET = 100_000
private const val HISTORY_CURSOR_PARTS = 3
private const val MIN_GIT_OBJECT_ID_LENGTH = 40
private const val MAX_GIT_OBJECT_ID_LENGTH = 64
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
