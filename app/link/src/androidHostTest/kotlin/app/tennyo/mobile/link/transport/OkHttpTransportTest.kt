package app.tennyo.mobile.link.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class OkHttpTransportTest {
    @Test
    fun `remote dispatcher stays below the companion ordinary channel budget`() {
        val dispatcher = okHttpDispatcher()

        assertEquals(MAX_LINK_REQUESTS, dispatcher.maxRequests)
        assertEquals(MAX_LINK_REQUESTS, dispatcher.maxRequestsPerHost)
        assertEquals(8, MAX_LINK_REQUESTS)
    }

    @Test
    fun `remote connection pool retains a bounded number of idle connections`() {
        val pool = okHttpConnectionPool()

        assertEquals(0, pool.connectionCount())
        assertEquals(0, pool.idleConnectionCount())
        assertEquals(8, MAX_IDLE_CONNECTIONS)
        assertEquals(15L, IDLE_CONNECTION_KEEP_ALIVE_SECONDS)
    }
}
