package app.tennyo.host.intellij.tests

import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.session.ConnectionRequestStatus
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.intellij.ConnectionRequestState
import app.tennyo.host.intellij.PopupScreen
import app.tennyo.host.intellij.parseDeviceOsInfo
import app.tennyo.host.intellij.popup.popupDeviceAvatarLabel
import app.tennyo.host.intellij.popupScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class PopupStateMappingTest {
    @Test
    fun `no session maps to the inactive screen`() {
        val screen = popupScreen(
            sessionId = null,
            pendingRequests = emptyList(),
            isConnected = false,
        )

        assertEquals(PopupScreen.Inactive, screen)
    }

    @Test
    fun `an open pairing window stays on the main screen`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = emptyList(),
            isConnected = false,
        )

        assertEquals(PopupScreen.Inactive, screen)
    }

    @Test
    fun `pending request maps to the request screen`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = listOf(request(status = ConnectionRequestStatus.PENDING)),
            isConnected = false,
        )

        assertInstanceOf(PopupScreen.ConnectionRequest::class.java, screen)
    }

    @Test
    fun `awaiting pairing maps to the pairing screen`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = listOf(request(status = ConnectionRequestStatus.AWAITING_PAIRING)),
            isConnected = false,
        )

        assertInstanceOf(PopupScreen.ConnectionPairing::class.java, screen)
    }

    @Test
    fun `connected session maps to the connected screen`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = listOf(request(status = ConnectionRequestStatus.AWAITING_PAIRING)),
            isConnected = true,
        )

        assertEquals(PopupScreen.Connected, screen)
    }

    @Test
    fun `pairing screen carries the awaiting pairing request when multiple requests exist`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = listOf(
                request(id = "pending", status = ConnectionRequestStatus.PENDING),
                request(id = "pairing", status = ConnectionRequestStatus.AWAITING_PAIRING),
            ),
            isConnected = false,
        )

        assertEquals("pairing", assertInstanceOf(PopupScreen.ConnectionPairing::class.java, screen).request.id)
    }

    @Test
    fun `request screen carries the pending request when multiple requests exist`() {
        val screen = popupScreen(
            sessionId = "session",
            pendingRequests = listOf(
                request(id = "accepted", status = ConnectionRequestStatus.ACCEPTED),
                request(id = "pending", status = ConnectionRequestStatus.PENDING),
            ),
            isConnected = false,
        )

        assertEquals("pending", assertInstanceOf(PopupScreen.ConnectionRequest::class.java, screen).request.id)
    }

    @Test
    fun `avatar label keeps short device token`() {
        assertEquals("iPr", popupDeviceAvatarLabel("iPad Pro"))
        assertEquals("iPa", popupDeviceAvatarLabel("iPa Pro"))
    }

    @Test
    fun `avatar label uses first letter and model token for numbered devices`() {
        assertEquals("P8", popupDeviceAvatarLabel("Pixel 8"))
        assertEquals("i15", popupDeviceAvatarLabel("iPhone 15"))
    }

    @Test
    fun `avatar label ignores OS suffix when device name is parsed before passing to avatar`() {
        // After parsing, "Pixel 8 Android 15" becomes "Pixel 8" for the avatar
        val (name, _) = parseDeviceOsInfo("Pixel 8 Android 15")
        assertEquals("P8", popupDeviceAvatarLabel(name))

        // Without parsing, the full label produces a different avatar label ("P15" from last token "15")
        assertEquals("P15", popupDeviceAvatarLabel("Pixel 8 Android 15"))
    }

    @Test
    fun `stale remembered pair ids include devices from a different companion identity`() {
        val stalePairIds = SessionHost.staleRememberedPairIds(
            currentCompanionId = "current-companion",
            summaries = listOf(
                RememberedPairSummary(
                    pairId = "pair-1",
                    deviceLabel = "Phone",
                    companionId = "old-companion",
                    expiresAtEpochMillis = 1_000L,
                )
            ),
        )

        assertEquals(setOf("pair-1"), stalePairIds)
    }

    @Test
    fun `parseDeviceOsInfo extracts Android version from device label`() {
        val (name, osInfo) = parseDeviceOsInfo("Pixel 8 Android 15")
        val (os, version) = osInfo

        assertEquals("Pixel 8", name)
        assertEquals("Android", os)
        assertEquals("15", version)
    }

    @Test
    fun `parseDeviceOsInfo extracts iOS version from device label`() {
        val (name, osInfo) = parseDeviceOsInfo("iPhone 15 iOS 17.4")
        val (os, version) = osInfo

        assertEquals("iPhone 15", name)
        assertEquals("iOS", os)
        assertEquals("17.4", version)
    }

    @Test
    fun `parseDeviceOsInfo returns full label when no OS suffix is present`() {
        val (name, osInfo) = parseDeviceOsInfo("My Laptop")
        val (os, version) = osInfo

        assertEquals("My Laptop", name)
        assertEquals(null, os)
        assertEquals(null, version)
    }

    @Test
    fun `parseDeviceOsInfo handles device label with trailing spaces`() {
        val (name, osInfo) = parseDeviceOsInfo("Pixel 8 Android 15  ")
        val (os, version) = osInfo

        assertEquals("Pixel 8", name)
        assertEquals("Android", os)
        assertEquals("15", version)
    }

    @Test
    fun `parseDeviceOsInfo does not match partial OS names`() {
        val (name, osInfo) = parseDeviceOsInfo("My AndroidStudio Device")
        val (os, version) = osInfo

        assertEquals("My AndroidStudio Device", name)
        assertEquals(null, os)
        assertEquals(null, version)
    }

    @Test
    fun `parseDeviceOsInfo ignores OS text in the middle of the label`() {
        val (name, osInfo) = parseDeviceOsInfo("Android Dev Phone")
        val (os, version) = osInfo

        assertEquals("Android Dev Phone", name)
        assertEquals(null, os)
        assertEquals(null, version)
    }

    @Test
    fun `parseDeviceOsInfo handles single-word device name with OS`() {
        val (name, osInfo) = parseDeviceOsInfo("iPad Android 14")
        val (os, version) = osInfo

        assertEquals("iPad", name)
        assertEquals("Android", os)
        assertEquals("14", version)
    }

    private fun request(
        status: ConnectionRequestStatus,
        id: String = "request",
    ): ConnectionRequestState =
        ConnectionRequestState(
            id = id,
            clientName = "Pixel 8",
            clientVersion = "1.0",
            deviceFingerprint = "abcd1234ef567890",
            pairingWords = listOf("Golden", "Meadow", "Comet", "Zephyr"),
            status = status,
        )
}
