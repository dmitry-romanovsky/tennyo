package app.tennyo.mobile.link.transport

import app.tennyo.contract.ErrorResponse
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.SessionResponse
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.isDeviceNotApproved
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class LinkErrorHandlingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun nonSuccessJsonErrorThrowsRemoteApiException(): Unit = runBlocking {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """{"error":"connection_request_pending","message":"Another mobile connection request is already pending."}""",
                        status = HttpStatusCode.Conflict,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            installLinkErrorHandling(json)
        }

        try {
            client.post("https://127.0.0.1/api/tennyo/link/pair").body<SessionResponse>()
            fail("Expected a LinkFault.Refused")
        } catch (error: LinkFault.Refused) {
            assertEquals(409, error.status)
            assertEquals("connection_request_pending", error.code)
            assertEquals("Another mobile connection request is already pending.", error.message)
        }
    }

    @Test
    fun parsesRemoteErrorResponse() {
        val error = json.decodeFromString<ErrorResponse>(
            """{"error":"connection_request_timed_out","message":"The connection request timed out."}""",
        )

        assertEquals("connection_request_timed_out", error.error)
        assertEquals("The connection request timed out.", error.message)
    }

    @Test
    fun switchingProtocolsIsAllowedForWebSocketUpgrade(): Unit = runBlocking {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "",
                        status = HttpStatusCode.SwitchingProtocols,
                    )
                }
            }
            installLinkErrorHandling(json)
        }

        val response = client.get("https://127.0.0.1/api/tennyo/link/channel")

        assertEquals(HttpStatusCode.SwitchingProtocols, response.status)
    }

    @Test
    fun channelFaultsAreClassifiedLikeHostErrors() {
        assertIs<LinkFault.SessionEnded>(LinkFault.fromLink(LinkFaultCode.SESSION_EXPIRED, "gone"))
        assertIs<LinkFault.SessionEnded>(LinkFault.fromLink(LinkFaultCode.SESSION_NOT_FOUND, "gone"))
        assertIs<LinkFault.Auth>(LinkFault.fromLink(LinkFaultCode.WRONG_DEVICE, "not this device"))
        assertIs<LinkFault.Auth>(LinkFault.fromLink(LinkFaultCode.REPLAYED, "seen"))
        assertIs<LinkFault.TargetMissing>(LinkFault.fromLink(LinkFaultCode.TARGET_MISSING, "gone"))
        assertIs<LinkFault.RateLimited>(LinkFault.fromLink(LinkFaultCode.BUSY, "busy"))
        assertIs<LinkFault.Refused>(LinkFault.fromLink(LinkFaultCode.OPERATION_FAILED, "failed"))
        assertTrue(LinkFault.fromLink(LinkFaultCode.WRONG_DEVICE, "not this device").isDeviceNotApproved)
    }
}
