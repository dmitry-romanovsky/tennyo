package app.tennyo.mobile.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.KnownHostReach
import app.tennyo.mobile.link.pairing.RemoteAddressError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Clock


private val NOW = Clock.System.now().toEpochMilliseconds()

private val KNOWN_HOSTS = listOf(
    KnownHostTargetUiState(
        companionId = "amber-harbour",
        companionLabel = "Amber Harbour",
        displayShortId = "abcd 1234 ef56",
        lastProjectName = "Tennyo",
        hostVersion = "Devin Desktop",
        lastUsedAtEpochMillis = NOW - 12L * 60_000L,
        networkLabel = "Office LAN",
        availability = KnownHostAvailability.AVAILABLE,
        actionEnabled = true,
        reach = KnownHostReach.NEARBY,
    ),
    KnownHostTargetUiState(
        companionId = "slate-meridian",
        companionLabel = "Slate Meridian",
        displayShortId = "31c8 05fa b972",
        lastProjectName = "Tennyo",
        hostVersion = "VS Code",
        lastUsedAtEpochMillis = NOW - 2L * 60L * 60_000L,
        networkLabel = "Home",
        availability = KnownHostAvailability.AVAILABLE,
        actionEnabled = true,
        reach = KnownHostReach.AWAY,
        resolvedAddress = "100.64.0.11",
    ),
    KnownHostTargetUiState(
        companionId = "quiet-lantern",
        companionLabel = "Quiet Lantern",
        displayShortId = "7f02 9be1 44da",
        lastProjectName = "Ktor demo",
        hostVersion = "VS Code 1.105",
        lastUsedAtEpochMillis = NOW - 3L * 24L * 60L * 60_000L,
        networkLabel = null,
        availability = KnownHostAvailability.UNAVAILABLE,
        actionEnabled = false,
    ),
)

private val PAIRING_HOSTS = listOf(
    PairingHostUiState(
        shortId = "31c805fab972",
        displayShortId = "31c8 05fa b972",
        hostName = "Slate Meridian",
        hostLabel = "VS Code 1.107",
        endpoint = "https://192.0.2.11:8443",
        expiresAtEpochMillis = NOW + 110_000L,
    ),
    PairingHostUiState(
        shortId = "c40d18e76a55",
        displayShortId = "c40d 18e7 6a55",
        hostName = "Copper Willow",
        hostLabel = "Cursor 1.6",
        endpoint = "https://192.0.2.12:8443",
        expiresAtEpochMillis = NOW + 46_000L,
    ),
)

/** Screen 02 with both lists populated, as the design shows it. */
@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ConnectScreenPairingPreview() {
    TennyoTheme {
        PreviewConnectScreen(
            ConnectState(knownHostTargets = KNOWN_HOSTS, pairingHosts = PAIRING_HOSTS),
        )
    }
}

/** Nothing found yet: the help card carries the whole screen. */
@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ConnectScreenEmptyPreview() {
    TennyoTheme {
        PreviewConnectScreen(ConnectState())
    }
}

private val LEARNED_ADDRESSES = listOf(
    "https://192.0.2.41:63343/api/tennyo",
    "https://100.64.0.11:63343/api/tennyo",
)

/** The addresses sheet while a freshly added address is being checked. */
@Preview(showBackground = true, widthDp = 420)
@Composable
private fun KnownHostAddressesCheckingPreview() {
    TennyoTheme {
        PreviewAddressesSheet(
            KnownHostAddressesUiState(
                targetId = "amber-harbour",
                learned = LEARNED_ADDRESSES,
                added = listOf("https://203.0.113.5:63343/api/tennyo"),
                checking = setOf("https://203.0.113.5:63343/api/tennyo"),
            ),
        )
    }
}

/** The addresses sheet after a check that found nothing, and after a typed address was refused. */
@Preview(showBackground = true, widthDp = 420)
@Composable
private fun KnownHostAddressesErrorPreview() {
    TennyoTheme {
        PreviewAddressesSheet(
            KnownHostAddressesUiState(
                targetId = "amber-harbour",
                learned = LEARNED_ADDRESSES,
                added = listOf("https://203.0.113.5:63343/api/tennyo"),
                input = "my_host.example",
                inputError = RemoteAddressError.NOT_AN_ADDRESS,
                check = AddressCheckUiState("https://203.0.113.5:63343/api/tennyo", AddressCheck.NO_ANSWER),
            ),
        )
    }
}

/** A host that has not been reached yet knows no address. */
@Preview(showBackground = true, widthDp = 420)
@Composable
private fun KnownHostAddressesEmptyPreview() {
    TennyoTheme {
        PreviewAddressesSheet(
            KnownHostAddressesUiState(targetId = "amber-harbour", learned = emptyList(), added = emptyList()),
        )
    }
}

@Composable
private fun PreviewAddressesSheet(addresses: KnownHostAddressesUiState) {
    KnownHostAddressesContent(
        addresses = addresses,
        onInputChanged = {},
        onAdd = {},
        onRemove = {},
        onDismiss = {},
    )
}

/** A host refusing new devices under its lockout, and a remembered-host attempt that failed. */
@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ConnectScreenBlockedPreview() {
    TennyoTheme {
        PreviewConnectScreen(
            ConnectState(
                knownHostTargets = KNOWN_HOSTS,
                pairingHosts = PAIRING_HOSTS.map { it.copy(isBlocked = true) },
                discoveryError = ConnectErrorUiState(
                    failure = ConnectFailure.KnownHostUnavailable,
                    details = UiText.Verbatim("SocketTimeoutException: connect timed out"),
                ),
            ),
        )
    }
}

@Composable
private fun PreviewConnectScreen(
    initialState: ConnectState,
) {
    val component = remember(initialState) {
        PreviewConnectComponent(initialState)
    }
    ConnectScreen(component = component)
}

private class PreviewConnectComponent(
    initialState: ConnectState,
) : ConnectComponent {
    override val state = MutableStateFlow(initialState)
    override val effects = emptyFlow<ConnectEffect>()

    override fun onIntent(intent: ConnectIntent) = Unit

    override fun onBack() = Unit
}
