package app.tennyo.mobile.link.pane

import app.tennyo.mobile.link.RequestBackoff
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

/**
 * The background requests of one pane, keyed by kind [K]: a kind the host rate-limited backs off, and every failure is
 * logged once — these requests have no user waiting on them, so they never surface as notices.
 */
class PaneRequests<K>(private val backoff: RequestBackoff<K> = RequestBackoff()) {
    fun allowed(requests: Set<K>, nowEpochMillis: Long): Set<K> = backoff.allowedRequests(requests, nowEpochMillis)

    fun succeeded(request: K) {
        backoff.onRequestSuccess(request)
    }

    fun failed(request: K, throwable: Throwable, message: String) {
        if (backoff.onRequestFailure(request, throwable, nowEpochMillis())) {
            Logger.w("$message; backing off after rate limit", throwable)
        } else {
            Logger.w(message, throwable)
        }
    }

    /** Runs [load] as [request], recording its outcome; `null` when it failed. Cancellation is not a failure. */
    suspend fun <R : Any> run(request: K, failureMessage: String, load: suspend () -> R): R? =
        runCatching { load() }
            .onSuccess { succeeded(request) }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                failed(request, throwable, failureMessage)
            }
            .getOrNull()
}
