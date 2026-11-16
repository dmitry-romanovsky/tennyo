package app.tennyo.host.engine.session

import app.tennyo.contract.SessionTimeouts
import app.tennyo.crypto.HostShortId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The window is the only thing standing between an unknown device and the claim step, so it must fail closed. */
class PairingWindowTest {

    private var now = 1_000L
    private val window = PairingWindow(wallClockMillis = { now }, drawName = { "Amber Harbour" })

    @Test
    fun `is closed until it is opened`() {
        assertNull(window.current())
        assertFalse(window.accepts("anything"))
    }

    @Test
    fun `derives the short id from the transport fingerprint rather than choosing it`() {
        val open = window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)

        assertEquals(HostShortId.of(FINGERPRINT), open.hostShortId)
    }

    @Test
    fun `closes itself once the deadline passes`() {
        window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)

        now += SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS

        assertNull(window.current())
    }

    @Test
    fun `extend restarts the deadline without re-rolling the name or the token`() {
        val opened = window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)
        now += SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS - 1

        val extended = assertNotNull(window.extend())

        assertEquals(opened.hostName, extended.hostName)
        assertEquals(opened.pairingToken, extended.pairingToken)
        assertEquals(now + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS, extended.expiresAtEpochMillis)
    }

    @Test
    fun `extend does nothing once the window has expired`() {
        window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)
        now += SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS

        assertNull(window.extend())
    }

    @Test
    fun `closing drops the token so no later request can authenticate with it`() {
        val opened = window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)
        assertTrue(window.accepts(opened.pairingToken))

        window.close()

        assertFalse(window.accepts(opened.pairingToken))
        assertNull(window.current())
    }

    @Test
    fun `refuses a token that is not the open window's`() {
        window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)

        assertFalse(window.accepts("not-the-token"))
    }

    @Test
    fun `draws a fresh token when it is opened again`() {
        val first = window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)
        val second = window.open(SESSION_ID, FINGERPRINT, HOST_LABEL)

        assertTrue(first.pairingToken != second.pairingToken)
        assertFalse(window.accepts(first.pairingToken))
    }

    private companion object {
        const val SESSION_ID = "session"
        const val FINGERPRINT = "tYRnHkuT6CF0VoVeJ6oVUmR6KpHPRmGSk8Lp0iGdGio"
        const val HOST_LABEL = "Cursor 1.5"
    }
}
