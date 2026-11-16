package app.tennyo.host.engine.panel

import app.tennyo.contract.Capability
import app.tennyo.contract.LinkJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel state is the contract between the two editors' panels, so it has to survive the round trip VS Code
 * puts it through — the webview receives it as JSON and renders it with no logic of its own.
 */
class HostPanelStateTest {

    @Test
    fun `every case survives the round trip the webview puts it through`() {
        ALL_CASES.forEach { state ->
            val json = LinkJson.encodeToString(HostPanelState.serializer(), state)

            assertEquals(state, LinkJson.decodeFromString(HostPanelState.serializer(), json))
        }
    }

    @Test
    fun `the six cases the panel renders are all reachable`() {
        assertEquals(
            listOf("NoSession", "Waiting", "Request", "Words", "WrongWords", "Connected"),
            ALL_CASES.map { it::class.simpleName },
        )
    }

    @Test
    fun `an editor without a listener says so rather than offering to start a session`() {
        val state = HostPanelState.NoSession(reason = NoSessionReason.LISTENER_UNAVAILABLE)

        assertEquals(NoSessionReason.LISTENER_UNAVAILABLE, state.reason)
        assertEquals(HostVisibility.KNOWN_DEVICES, state.visibility)
    }

    @Test
    fun `wrong words keeps the words and spends only the attempt budget`() {
        val words = HostPanelState.Words(
            visibility = HostVisibility.ALL,
            knownDeviceCount = 1,
            words = WORDS,
            attemptsLeft = 3,
            expiresAtEpochMillis = DEADLINE,
        )

        val wrong = HostPanelState.WrongWords(
            visibility = words.visibility,
            knownDeviceCount = words.knownDeviceCount,
            words = words.words,
            attemptsLeft = words.attemptsLeft - 1,
            expiresAtEpochMillis = words.expiresAtEpochMillis,
        )

        assertEquals(words.words, wrong.words)
        assertEquals(2, wrong.attemptsLeft)
    }

    @Test
    fun `visibility is one three-way answer, not a boolean`() {
        assertEquals(
            listOf("NO_ONE", "KNOWN_DEVICES", "ALL"),
            HostVisibility.entries.map { it.name },
        )
    }

    @Test
    fun `every case carries the standing visibility and known-device count`() {
        ALL_CASES.forEach { state ->
            assertTrue(state.visibility in HostVisibility.entries, "missing visibility on $state")
            assertTrue(state.knownDeviceCount >= 0, "missing known-device count on $state")
        }
    }

    private companion object {
        const val DEADLINE = 1_700_000_000_000L
        val WORDS = listOf("Golden", "Meadow", "Comet", "Zephyr")

        val DEVICE = HostPanelDevice(
            name = "Pixel 8",
            platform = "Android 15",
            shortId = "abcd1234ef56",
        )

        val ALL_CASES: List<HostPanelState> = listOf(
            HostPanelState.NoSession(knownDeviceCount = 1),
            HostPanelState.Waiting(
                visibility = HostVisibility.ALL,
                knownDeviceCount = 1,
                hostName = "Amber Harbour",
                hostShortId = "abcd1234ef56",
                expiresAtEpochMillis = DEADLINE,
            ),
            HostPanelState.Request(
                visibility = HostVisibility.ALL,
                knownDeviceCount = 1,
                device = DEVICE,
                expiresAtEpochMillis = DEADLINE,
            ),
            HostPanelState.Words(
                visibility = HostVisibility.ALL,
                knownDeviceCount = 1,
                words = WORDS,
                attemptsLeft = 3,
                expiresAtEpochMillis = DEADLINE,
            ),
            HostPanelState.WrongWords(
                visibility = HostVisibility.ALL,
                knownDeviceCount = 1,
                words = WORDS,
                attemptsLeft = 2,
                expiresAtEpochMillis = DEADLINE,
            ),
            HostPanelState.Connected(
                visibility = HostVisibility.KNOWN_DEVICES,
                knownDeviceCount = 2,
                device = DEVICE.copy(isNew = false),
                capabilities = setOf(Capability.FILES, Capability.GIT),
            ),
        )
    }
}
