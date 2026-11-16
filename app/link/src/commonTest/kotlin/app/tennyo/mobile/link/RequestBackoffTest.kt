package app.tennyo.mobile.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestBackoffTest {
    private enum class Request { Tabs, Snapshot, Content }

    private val rateLimit = LinkFault.fromHost(status = 429, code = "http_429", message = "Too many requests")

    @Test
    fun rateLimitIsRecognizedThroughWrappedFailures() {
        assertTrue(IllegalStateException("request failed", rateLimit).isRateLimited)
    }

    @Test
    fun rateLimitBacksOffOnlyTheNoisyRequest() {
        val policy = RequestBackoff<Request>(rateLimitBackoffMillis = 30_000L)

        assertTrue(policy.onRequestFailure(Request.Tabs, rateLimit, nowEpochMillis = 0L))

        assertFalse(policy.shouldRun(Request.Tabs, nowEpochMillis = 29_999L))
        assertTrue(policy.shouldRun(Request.Snapshot, nowEpochMillis = 29_999L))
        assertTrue(policy.shouldRun(Request.Tabs, nowEpochMillis = 30_000L))
    }

    @Test
    fun nonRateLimitFailureDoesNotBackOffRequest() {
        val policy = RequestBackoff<Request>(rateLimitBackoffMillis = 30_000L)

        assertFalse(policy.onRequestFailure(Request.Content, LinkFault.fromHost(500, "http_500", "Server error"), 0L))
        assertTrue(policy.shouldRun(Request.Content, nowEpochMillis = 1L))
    }

    @Test
    fun successfulRequestClearsMatchingBackoff() {
        val policy = RequestBackoff<Request>(rateLimitBackoffMillis = 30_000L)
        policy.onRequestFailure(Request.Snapshot, rateLimit, nowEpochMillis = 0L)

        policy.onRequestSuccess(Request.Snapshot)

        assertTrue(policy.shouldRun(Request.Snapshot, nowEpochMillis = 1L))
    }

    @Test
    fun filterKeepsRequestsWhoseBackoffHasExpired() {
        val policy = RequestBackoff<Request>(rateLimitBackoffMillis = 30_000L)
        policy.onRequestFailure(Request.Content, rateLimit, nowEpochMillis = 0L)
        val both = setOf(Request.Tabs, Request.Content)

        assertEquals(setOf(Request.Tabs), policy.allowedRequests(both, nowEpochMillis = 0L))
        assertEquals(both, policy.allowedRequests(both, nowEpochMillis = 30_000L))
    }
}
