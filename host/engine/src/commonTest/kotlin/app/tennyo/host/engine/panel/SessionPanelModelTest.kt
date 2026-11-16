package app.tennyo.host.engine.panel

import app.tennyo.host.engine.auth.deviceKeyHash
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.OverlayEndpoint
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.TestDevice
import app.tennyo.host.engine.session.openPairingWindow
import app.tennyo.host.engine.session.testPanes
import app.tennyo.host.engine.transport.OverlayNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The panel follows the pairing flow step by step: a request waits for the user's answer before any words are shown,
 * because the phone is waiting on that answer too. Every request carries the session's words from the start, so the
 * panel must go by the request's status, never by whether words exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionPanelModelTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val platform = FakeHostPlatform(discoveryEnabled = true)
    private val host = SessionHost(testPanes(), platform, scope)
    private val actions = RecordingActions()
    private val panel = SessionPanelModel(host, actions)

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `a new device is a request to answer before any words are shown`() = runTest {
        host.openPairingWindow()
        backgroundScope.requestFrom(TestDevice())
        runCurrent()

        assertIs<HostPanelState.Request>(panel.state)

        panel.acceptRequest()

        assertIs<HostPanelState.Words>(panel.state)
    }

    @Test
    fun `a device resuming its remembered pair is never shown the words`() = runTest {
        host.openPairingWindow()
        val device = TestDevice()
        val pair = newRememberedPair(deviceLabel = "Pixel", deviceKeyHash = deviceKeyHash(device.publicKey))
            .second.copy(companionId = platform.companionId())
        host.saveRememberedPair(pair)

        backgroundScope.requestFrom(device, rememberedPairId = pair.pairId)
        runCurrent()

        assertIs<HostPanelState.Waiting>(panel.state)
    }

    private fun CoroutineScope.requestFrom(device: TestDevice, rememberedPairId: String? = null) {
        val auth = host.activeSession()!!.auth
        launch {
            auth.requestConnectionApproval(
                clientId = "android",
                clientName = "Pixel",
                clientVersion = "1.0",
                devicePublicKey = device.publicKey,
                rememberedPairId = rememberedPairId,
            )
        }
    }

    @Test
    fun `the remote access switch is unavailable while visibility is NO_ONE`() {
        platform.discoveryEnabled = false
        assertFalse(panel.remoteAccess.available)

        platform.discoveryEnabled = true
        assertTrue(panel.remoteAccess.available)
    }

    @Test
    fun `RemoteAccessPanel lists overlay addresses`() {
        platform.remoteAccess = true
        platform.endpoints = platform.endpoints.copy(
            overlay = listOf(
                OverlayEndpoint(OverlayNetwork.TAILNET_RANGE, "100.101.2.3", "https://100.101.2.3:63342/api/tennyo"),
                OverlayEndpoint(OverlayNetwork.ZEROTIER, "10.147.17.5", "https://10.147.17.5:63342/api/tennyo"),
            ),
        )

        assertEquals(
            RemoteAccessPanel(
                enabled = true,
                available = true,
                port = 63342,
                overlay = listOf(
                    OverlayAddress(OverlayNetwork.TAILNET_RANGE, "100.101.2.3"),
                    OverlayAddress(OverlayNetwork.ZEROTIER, "10.147.17.5"),
                ),
            ),
            panel.remoteAccess,
        )
    }

    @Test
    fun `an address that appears after the session started shows in the panel`() {
        assertEquals(emptyList(), panel.remoteAccess.overlay)

        platform.endpoints = platform.endpoints.copy(
            overlay = listOf(OverlayEndpoint(OverlayNetwork.WIREGUARD, "10.8.0.2", "https://10.8.0.2:63342/api/tennyo")),
        )

        assertEquals(listOf(OverlayAddress(OverlayNetwork.WIREGUARD, "10.8.0.2")), panel.remoteAccess.overlay)
    }

    @Test
    fun `switching remote access asks the host`() {
        panel.setRemoteAccess(true)

        assertEquals(listOf(true), actions.remoteAccessRequests)
    }

    private class RecordingActions : HostPanelActions {
        val remoteAccessRequests = mutableListOf<Boolean>()

        override fun openPairingWindow() = Unit

        override fun setDiscoveryEnabled(enabled: Boolean) = Unit

        override fun setRemoteAccess(enabled: Boolean) {
            remoteAccessRequests += enabled
        }
    }

}
