package app.tennyo.mobile.feature.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the pairing screen's rows move. The loop decides which hosts survive a pass; the reducer only records the
 * result, and nothing here depends on visible text.
 */
class PairingRowsTest {

    @Test
    fun `a probed host becomes a row`() {
        val state = reduceConnect(ConnectState(), ConnectMsg.Pairing.HostsProbed(listOf(SLATE)))

        assertEquals(listOf(SLATE.shortId), state.pairingHosts.map { it.shortId })
    }

    @Test
    fun `rows are ordered by name so the list does not reshuffle between passes`() {
        val state = reduceConnect(ConnectState(), ConnectMsg.Pairing.HostsProbed(listOf(SLATE, COPPER)))

        assertEquals(listOf(COPPER.hostName, SLATE.hostName), state.pairingHosts.map { it.hostName })
    }

    @Test
    fun `a host drops off once its offer stops being reported`() {
        val listed = reduceConnect(ConnectState(), ConnectMsg.Pairing.HostsProbed(listOf(SLATE, COPPER)))

        val after = reduceConnect(listed, ConnectMsg.Pairing.HostsProbed(listOf(COPPER)))

        assertEquals(listOf(COPPER.shortId), after.pairingHosts.map { it.shortId })
    }

    @Test
    fun `a selection is released when the host it named is gone`() {
        val selected = reduceConnect(
            reduceConnect(ConnectState(), ConnectMsg.Pairing.HostsProbed(listOf(SLATE))),
            ConnectMsg.Pairing.HostSelected(SLATE.shortId),
        )
        assertEquals(SLATE.shortId, selected.selectedPairingHostId)

        val after = reduceConnect(selected, ConnectMsg.Pairing.HostsProbed(emptyList()))

        assertNull(after.selectedPairingHostId)
    }

    @Test
    fun `a blocked host is carried as typed state rather than as a message`() {
        val state = reduceConnect(
            ConnectState(),
            ConnectMsg.Pairing.HostsProbed(listOf(SLATE.copy(isBlocked = true))),
        )

        assertTrue(state.pairingHosts.single().isBlocked)
    }

    @Test
    fun `only an available known host can be tapped and only while nothing else is connecting`() {
        val row = KnownHostTargetUiState(
            companionId = "amber",
            companionLabel = "Amber Harbour",
            displayShortId = "abcd 1234 ef56",
            lastProjectName = "Tennyo",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = 0,
            networkLabel = null,
            availability = KnownHostAvailability.AVAILABLE,
            actionEnabled = false,
        )

        assertTrue(row.withActionEnabled(selectedTargetId = null).actionEnabled)
        assertTrue(row.withActionEnabled(selectedTargetId = "amber").actionEnabled)
        assertFalse(row.withActionEnabled(selectedTargetId = "other").actionEnabled)
        assertFalse(
            row.copy(availability = KnownHostAvailability.UNAVAILABLE).withActionEnabled(null).actionEnabled,
        )
    }

    @Test
    fun `a host this phone knows and reaches is offered once as its known row`() {
        val known = KnownHostTargetUiState(
            companionId = "slate",
            companionLabel = "VS Code",
            displayShortId = SLATE.displayShortId,
            lastProjectName = "Tennyo",
            hostVersion = "2026.3",
            lastUsedAtEpochMillis = 0,
            networkLabel = null,
            availability = KnownHostAvailability.AVAILABLE,
            actionEnabled = true,
        )
        val state = ConnectState(knownHostTargets = listOf(known), pairingHosts = listOf(SLATE, COPPER))

        assertEquals(listOf(COPPER.shortId), state.visiblePairingHosts.map { it.shortId })
        val unreachable = state.copy(
            knownHostTargets = listOf(known.copy(availability = KnownHostAvailability.UNAVAILABLE)),
        )
        assertEquals(listOf(SLATE.shortId, COPPER.shortId), unreachable.visiblePairingHosts.map { it.shortId })
    }

    private companion object {
        val SLATE = PairingHostUiState(
            shortId = "31c805fab972",
            displayShortId = "31c8 05fa b972",
            hostName = "Slate Meridian",
            hostLabel = "VS Code 1.107",
            endpoint = "https://192.0.2.11:8443",
            expiresAtEpochMillis = 120_000L,
        )
        val COPPER = PairingHostUiState(
            shortId = "c40d18e76a55",
            displayShortId = "c40d 18e7 6a55",
            hostName = "Copper Willow",
            hostLabel = "Cursor 1.6",
            endpoint = "https://192.0.2.12:8443",
            expiresAtEpochMillis = 90_000L,
        )
    }
}
