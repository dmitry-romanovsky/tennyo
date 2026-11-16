package app.tennyo.mobile.feature.connect

import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.RemoteAddressError
import app.tennyo.mobile.link.pairing.RemoteAddressParse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectAddressesReducerTest {
    private val learned = "https://192.168.1.41:63343/api/tennyo"
    private val typed = "https://203.0.113.5:63343/api/tennyo"

    private fun opened() = reduceConnect(
        ConnectState(),
        ConnectMsg.Addresses.Opened("host-1", learned = listOf(learned), added = emptyList()),
    )

    private fun invalid(error: RemoteAddressError) = ConnectMsg.Addresses.Added(RemoteAddressParse.Invalid(error))

    @Test
    fun `opening shows the learned and added addresses of one host`() {
        assertEquals(KnownHostAddressesUiState("host-1", listOf(learned), emptyList()), opened().addresses)
    }

    @Test
    fun `typing clears the previous input error`() {
        val withError = reduceConnect(opened(), invalid(RemoteAddressError.EMPTY))

        val typing = reduceConnect(withError, ConnectMsg.Addresses.InputChanged("abc"))

        assertEquals("abc", typing.addresses?.input)
        assertNull(typing.addresses?.inputError)
    }

    @Test
    fun `an invalid address records its error and adds nothing`() {
        val state = reduceConnect(opened(), invalid(RemoteAddressError.BAD_PORT))

        assertEquals(RemoteAddressError.BAD_PORT, state.addresses?.inputError)
        assertTrue(state.addresses?.added.orEmpty().isEmpty())
        assertEquals(false, state.addresses?.isChecking)
    }

    @Test
    fun `a valid address is added once and clears the input and starts a check`() {
        val typing = reduceConnect(opened(), ConnectMsg.Addresses.InputChanged("203.0.113.5"))

        val state = listOf(typed, typed).fold(typing) { acc, endpoint ->
            reduceConnect(acc, ConnectMsg.Addresses.Added(RemoteAddressParse.Valid(endpoint)))
        }

        assertEquals(listOf(typed), state.addresses?.added)
        assertEquals("", state.addresses?.input)
        assertEquals(true, state.addresses?.isChecking)
    }

    @Test
    fun `a check result lands on its sheet and ends the checking state`() {
        val checking = reduceConnect(opened(), ConnectMsg.Addresses.Added(RemoteAddressParse.Valid(typed)))

        val state = reduceConnect(checking, ConnectMsg.Addresses.Checked("host-1", typed, AddressCheck.REACHED))

        assertEquals(AddressCheckUiState(typed, AddressCheck.REACHED), state.addresses?.check)
        assertEquals(false, state.addresses?.isChecking)
    }

    @Test
    fun `a stale check for a closed or another sheet is ignored`() {
        val checking = reduceConnect(opened(), ConnectMsg.Addresses.Added(RemoteAddressParse.Valid(typed)))
        val closed = reduceConnect(checking, ConnectMsg.Addresses.Closed)

        assertNull(reduceConnect(closed, ConnectMsg.Addresses.Checked("host-1", typed, AddressCheck.REACHED)).addresses)
        val otherHost = reduceConnect(checking, ConnectMsg.Addresses.Checked("host-2", typed, AddressCheck.REACHED))
        assertNull(otherHost.addresses?.check)
        assertEquals(true, otherHost.addresses?.isChecking)
    }

    @Test
    fun `removing an address drops it and its check`() {
        val checked = reduceConnect(
            reduceConnect(opened(), ConnectMsg.Addresses.Added(RemoteAddressParse.Valid(typed))),
            ConnectMsg.Addresses.Checked("host-1", typed, AddressCheck.NO_ANSWER),
        )

        val state = reduceConnect(checked, ConnectMsg.Addresses.Removed(typed))

        assertTrue(state.addresses?.added.orEmpty().isEmpty())
        assertNull(state.addresses?.check)
    }

    @Test
    fun `closing the sheet clears it`() {
        assertNull(reduceConnect(opened(), ConnectMsg.Addresses.Closed).addresses)
    }

    @Test
    fun `an address already listed is not added or checked again`() {
        val state = reduceConnect(opened(), ConnectMsg.Addresses.Added(RemoteAddressParse.Valid("https://192.168.1.41:63343/api/tennyo")))

        assertTrue(state.addresses?.added.orEmpty().isEmpty())
        assertEquals(false, state.addresses?.isChecking)
    }

    @Test
    fun `a check answer ends only the checking of its own address`() {
        val other = "https://198.51.100.9:63343/api/tennyo"
        val both = listOf(typed, other).fold(opened()) { acc, endpoint ->
            reduceConnect(acc, ConnectMsg.Addresses.Added(RemoteAddressParse.Valid(endpoint)))
        }

        val state = reduceConnect(both, ConnectMsg.Addresses.Checked("host-1", typed, AddressCheck.REACHED))

        assertEquals(setOf(other), state.addresses?.checking)
        assertEquals(true, state.addresses?.isChecking)
    }
}
