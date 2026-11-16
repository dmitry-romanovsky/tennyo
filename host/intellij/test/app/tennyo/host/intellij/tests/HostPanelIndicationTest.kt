package app.tennyo.host.intellij.tests

import app.tennyo.contract.Capability
import app.tennyo.host.engine.panel.HostPanelDevice
import app.tennyo.host.engine.panel.HostPanelState
import app.tennyo.host.engine.panel.HostVisibility
import app.tennyo.host.intellij.HostStatusIndication
import app.tennyo.host.intellij.toStatusIndication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The status bar reads the same six panel cases the popup draws, so the two can never disagree about where the
 * pairing flow is.
 */
internal class HostPanelIndicationTest {

    @Test
    fun `nothing running with nothing visible is idle`() {
        val state = HostPanelState.NoSession(visibility = HostVisibility.NO_ONE)

        assertEquals(HostStatusIndication.IDLE, state.toStatusIndication())
    }

    @Test
    fun `nothing running but reachable by known devices is discovering`() {
        val state = HostPanelState.NoSession(visibility = HostVisibility.KNOWN_DEVICES)

        assertEquals(HostStatusIndication.DISCOVERING, state.toStatusIndication())
    }

    @Test
    fun `an open pairing window is waiting`() {
        val state = HostPanelState.Waiting(
            visibility = HostVisibility.ALL,
            knownDeviceCount = 0,
            hostName = "Amber Harbour",
            hostShortId = "abcd1234ef56",
            expiresAtEpochMillis = DEADLINE,
        )

        assertEquals(HostStatusIndication.WAITING, state.toStatusIndication())
    }

    @Test
    fun `a request and both word states all need the user`() {
        val request = HostPanelState.Request(
            visibility = HostVisibility.ALL,
            knownDeviceCount = 0,
            device = DEVICE,
            expiresAtEpochMillis = DEADLINE,
        )
        val words = HostPanelState.Words(
            visibility = HostVisibility.ALL,
            knownDeviceCount = 0,
            words = WORDS,
            attemptsLeft = 3,
            expiresAtEpochMillis = DEADLINE,
        )
        val wrongWords = HostPanelState.WrongWords(
            visibility = HostVisibility.ALL,
            knownDeviceCount = 0,
            words = WORDS,
            attemptsLeft = 2,
            expiresAtEpochMillis = DEADLINE,
        )

        assertEquals(HostStatusIndication.PENDING_REQUEST, request.toStatusIndication())
        assertEquals(HostStatusIndication.PENDING_REQUEST, words.toStatusIndication())
        assertEquals(HostStatusIndication.PENDING_REQUEST, wrongWords.toStatusIndication())
    }

    @Test
    fun `a live session is connected`() {
        val state = HostPanelState.Connected(
            visibility = HostVisibility.KNOWN_DEVICES,
            knownDeviceCount = 1,
            device = DEVICE.copy(isNew = false),
            capabilities = setOf(Capability.FILES),
        )

        assertEquals(HostStatusIndication.CONNECTED, state.toStatusIndication())
    }

    private companion object {
        const val DEADLINE = 1_700_000_000_000L
        val WORDS = listOf("Golden", "Meadow", "Comet", "Zephyr")
        val DEVICE = HostPanelDevice(name = "Pixel 8", platform = "Android 15", shortId = "abcd1234ef56")
    }
}
