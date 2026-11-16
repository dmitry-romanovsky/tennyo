package app.tennyo.host.engine.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pairing turns discovery on; the mode the user had before comes back when the pairing session ends. */
class DiscoveryBeforePairingTest {
    private val memory = DiscoveryBeforePairing()

    @Test
    fun `ending a session restores the mode chosen before pairing`() {
        memory.remember(discoveryEnabled = false)

        assertEquals(false, memory.takeSaved())
        assertNull(memory.takeSaved())
    }

    @Test
    fun `re-opening the window keeps the first mode, not the one pairing forced`() {
        memory.remember(discoveryEnabled = false)
        memory.remember(discoveryEnabled = true)

        assertEquals(false, memory.takeSaved())
    }

    @Test
    fun `an explicit choice while pairing is not overwritten on session end`() {
        memory.remember(discoveryEnabled = false)

        memory.forget()

        assertNull(memory.takeSaved())
    }
}
