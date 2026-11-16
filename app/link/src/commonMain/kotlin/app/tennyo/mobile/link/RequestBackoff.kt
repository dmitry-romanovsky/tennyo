package app.tennyo.mobile.link

/**
 * Per-request backoff after the host rate-limits: a request of kind [K] that was refused with
 * [LinkFault.RateLimited] is skipped until the backoff has passed. Other failures do not back off.
 */
class RequestBackoff<K>(
    private val rateLimitBackoffMillis: Long = DEFAULT_RATE_LIMIT_BACKOFF_MILLIS,
) {
    private val retryAfterByRequest = mutableMapOf<K, Long>()

    fun shouldRun(request: K, nowEpochMillis: Long): Boolean =
        nowEpochMillis >= (retryAfterByRequest[request] ?: 0L)

    fun allowedRequests(requests: Set<K>, nowEpochMillis: Long): Set<K> =
        requests.filterTo(mutableSetOf()) { request -> shouldRun(request, nowEpochMillis) }

    fun onRequestSuccess(request: K) {
        retryAfterByRequest.remove(request)
    }

    /** Records [throwable] for [request]; `true` when it was a rate limit and the request now backs off. */
    fun onRequestFailure(request: K, throwable: Throwable, nowEpochMillis: Long): Boolean {
        val rateLimited = throwable.isRateLimited
        if (rateLimited) {
            retryAfterByRequest[request] = nowEpochMillis + rateLimitBackoffMillis
        }
        return rateLimited
    }

    private companion object {
        const val DEFAULT_RATE_LIMIT_BACKOFF_MILLIS = 30_000L
    }
}
