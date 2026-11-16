package app.tennyo.mobile.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.tennyo.mobile.design.ErrorDetailsSheet
import app.tennyo.mobile.design.layout.PlatformTopAppBar
import app.tennyo.mobile.design.layout.PlatformTopAppBarSize
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.connect_title
import org.jetbrains.compose.resources.stringResource

/**
 * Screen 02: the hosts this device knows, then the hosts in pairing mode nearby, then the help card. Both lists
 * refresh themselves once a second while this screen is resumed — there is no refresh gesture to offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    component: ConnectComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var fullErrorText by remember { mutableStateOf<String?>(null) }
    val notificationPermissionController = rememberNotificationPermissionController(component)
    val requestNotificationPermission by rememberUpdatedState(notificationPermissionController.request)

    LaunchedEffect(component, notificationPermissionController.status) {
        component.onIntent(
            ConnectIntent.NotificationPermissionStatusLoaded(notificationPermissionController.status),
        )
    }

    LaunchedEffect(component) {
        component.effects.collect { effect ->
            when (effect) {
                ConnectEffect.RequestNotificationPermission -> requestNotificationPermission()
            }
        }
    }

    if (state.notificationPermission.explanationVisible) {
        NotificationPermissionDialog(component = component)
    }

    state.projectSelection?.let { projectSelection ->
        ProjectSelectionSheet(
            projectSelection = projectSelection,
            isStartingSession = state.isStartingDiscoverySession,
            onDismiss = { component.onIntent(ConnectIntent.ProjectSelectionDismissed) },
            onProjectSelected = { projectId ->
                component.onIntent(
                    ConnectIntent.ProjectSelectionConfirmed(
                        targetId = projectSelection.targetId,
                        projectId = projectId,
                    ),
                )
            },
        )
    }

    state.addresses?.let { addresses ->
        KnownHostAddressesSheet(
            addresses = addresses,
            onInputChanged = { component.onIntent(ConnectIntent.AddressInputChanged(it)) },
            onAdd = { component.onIntent(ConnectIntent.AddressSubmitted) },
            onRemove = { component.onIntent(ConnectIntent.AddressRemoveClicked(it)) },
            onDismiss = { component.onIntent(ConnectIntent.AddressesDismissed) },
        )
    }

    fullErrorText?.let { errorText ->
        ModalBottomSheet(
            onDismissRequest = { fullErrorText = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ErrorDetailsSheet(
                errorText = errorText,
                onCopy = { clipboardManager.setText(AnnotatedString(errorText)) },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(),
        topBar = {
            PlatformTopAppBar(
                title = stringResource(Res.string.connect_title),
                onBack = component::onBack,
                actions = {},
                modifier = Modifier.fillMaxWidth(),
                size = PlatformTopAppBarSize.Large,
                bottomContent = null,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.extraLarge),
            contentPadding = PaddingValues(
                top = Spacing.large,
                bottom = safeDrawingBottomInset(Spacing.large),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
        ) {
            item(key = "known") {
                ConnectSecondaryBlock(
                    state = state,
                    onShowErrorDetails = { details -> fullErrorText = details },
                    onForgetKnownHost = { targetId ->
                        component.onIntent(ConnectIntent.ForgetKnownHostClicked(targetId))
                    },
                    onConnectKnownHost = { targetId ->
                        component.onIntent(ConnectIntent.StartSessionAndConnectClicked(targetId))
                    },
                    onShowAddresses = { targetId -> component.onIntent(ConnectIntent.AddressesClicked(targetId)) },
                )
            }
            item(key = "pairing") {
                PairingHostSection(
                    hosts = state.visiblePairingHosts,
                    onHostSelected = { shortId ->
                        component.onIntent(ConnectIntent.PairingHostSelected(shortId))
                    },
                )
            }
            item(key = "help") {
                PairingHelpCard()
            }
        }
    }
}
