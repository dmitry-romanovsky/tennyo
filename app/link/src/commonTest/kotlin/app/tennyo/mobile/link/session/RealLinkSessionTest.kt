package app.tennyo.mobile.link.session

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkCall
import app.tennyo.mobile.link.ConnectStep
import app.tennyo.mobile.link.DegradedReason
import app.tennyo.mobile.link.EndReason
import app.tennyo.mobile.link.LinkAttachment
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkSessions
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.FirstPairing
import app.tennyo.mobile.link.pairing.KnownHosts
import app.tennyo.mobile.link.pairing.NoopKnownHostTargetStore
import app.tennyo.mobile.link.pairing.NoopRememberedPairStore
import app.tennyo.mobile.link.pairing.RememberedPairResume
import app.tennyo.mobile.link.pairing.RememberedPairs
import app.tennyo.mobile.link.pairing.SessionEndpoints
import app.tennyo.mobile.link.transport.LinkChannels
import app.tennyo.mobile.link.transport.LinkHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The session's own transitions, driven through fake services with no network behind them. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealLinkSessionTest {
    private val notification = RecordingNotification()

    @Test
    fun startsOutReachingTheHost() {
        val session = session(VALID_TICKET)

        assertEquals(LinkState.Connecting(ConnectStep.Reaching), session.state.value)
    }

    @Test
    fun anUnreachableHostDegradesTheSessionWithTheFault() {
        val session = session(VALID_TICKET)

        session.attach(NoopAttachment)

        val degraded = assertIs<LinkState.Degraded>(session.awaitState { it is LinkState.Degraded })
        val reason = assertIs<DegradedReason.Failed>(degraded.reason)
        assertIs<LinkFault.Transport>(reason.fault)
    }

    @Test
    fun endingTheSessionIsFinalAndStopsItsNotification() {
        val session = session(VALID_TICKET)
        session.attach(NoopAttachment)

        session.end()

        assertEquals(LinkState.Ended(EndReason.ByUser), session.awaitState { it is LinkState.Ended })
        assertTrue(notification.stopped)
    }

    @Test
    fun theNotificationCanEndTheSession() {
        val session = session(VALID_TICKET)
        session.attach(NoopAttachment)

        notification.endSessionRequests.tryEmit(VALID_TICKET.toStoredArtifact())

        assertEquals(LinkState.Ended(EndReason.FromNotification), session.state.value)
    }

    @Test
    fun reopeningTheSameTicketKeepsTheRunningSessionAndAnEndedOneIsReplaced() {
        val sessions = LinkSessions { ticket -> session(ticket) }
        val first = sessions.open(VALID_TICKET)

        assertSame(first, sessions.open(VALID_TICKET))

        first.attach(NoopAttachment)
        first.end()
        (first as RealLinkSession).awaitState { it is LinkState.Ended }

        assertNotSame(first, sessions.open(VALID_TICKET))
    }

    @Test
    fun aCallWithoutAnOpenChannelFailsAsAClosedChannel() {
        val session = session(VALID_TICKET)

        val fault = runCatching { runBlocking { session.call(LinkCall.Stats) } }.exceptionOrNull()

        assertIs<LinkFault.StreamClosed>(fault)
    }

    private fun RealLinkSession.awaitState(predicate: (LinkState) -> Boolean): LinkState =
        runBlocking { withTimeout(AWAIT_MILLIS) { state.first(predicate) } }

    private fun session(ticket: LinkTicket): RealLinkSession {
        val identity = DeviceIdentity()
        val http = LinkHttp(httpClient = unreachableClient(), deviceIdentity = identity)
        val rememberedPairs = RememberedPairs(NoopRememberedPairStore)
        val knownHosts = KnownHosts(NoopKnownHostTargetStore, NoopRememberedPairStore)
        return RealLinkSession(
            sessionTicket = ticket,
            services = LinkServices(
                http = http,
                endpoints = SessionEndpoints(http, identity, rememberedPairs, knownHosts, clientVersion = "test"),
                firstPairing = FirstPairing(http, identity, NoopRememberedPairStore, knownHosts),
                rememberedPairResume = RememberedPairResume(http, identity, rememberedPairs, knownHosts),
                channels = LinkChannels(httpClient = unreachableClient(), deviceIdentity = identity),
                credentials = SessionCredentialStore(FakeSecureStorage()),
                notification = notification,
            ),
            mainContext = UnconfinedTestDispatcher(),
        )
    }

    private fun unreachableClient(): HttpClient =
        HttpClient(MockEngine) { engine { addHandler { throw IllegalStateException("host unreachable") } } }

    private object NoopAttachment : LinkAttachment {
        override suspend fun loadSnapshot(capabilities: Set<Capability>) = Unit
        override fun suspendSync(forBackground: Boolean) = Unit
    }

    private class RecordingNotification : SessionNotification {
        override val endSessionRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
        var stopped = false

        override fun start(info: SessionNotificationInfo) = Unit
        override fun update(info: SessionNotificationInfo) = Unit
        override fun endSession() = Unit
        override fun stop(sessionId: String) {
            stopped = true
        }
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L

        val VALID_TICKET = LinkTicket(
            endpoint = "https://192.168.1.10:63342/api/tennyo",
            sessionId = "session-1",
            token = "token-1",
            fingerprint = "fingerprint-1",
            projectName = "Project",
            projectPath = "",
        )
    }
}
