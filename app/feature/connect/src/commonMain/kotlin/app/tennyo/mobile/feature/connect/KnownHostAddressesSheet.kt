package app.tennyo.mobile.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoBottomSheetHeader
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.TennyoFocusedTextField
import app.tennyo.mobile.design.TennyoProgressIndicator
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.connect.generated.resources.Res
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_add
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_added_section
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_check_no_answer
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_check_reached
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_check_refused
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_checking
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_description
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_done
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_empty
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_bad_port
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_empty
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_host_names
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_ipv6
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_not_a_host
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_error_not_an_address
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_input_label
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_learned_section
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_remove
import app.tennyo.mobile.feature.connect.generated.resources.connect_addresses_title
import app.tennyo.mobile.link.pairing.AddressCheck
import app.tennyo.mobile.link.pairing.RemoteAddressError
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnownHostAddressesSheet(
    addresses: KnownHostAddressesUiState,
    onInputChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        contentWindowInsets = { WindowInsets() },
        modifier = modifier,
    ) {
        KnownHostAddressesContent(
            addresses = addresses,
            onInputChanged = onInputChanged,
            onAdd = onAdd,
            onRemove = onRemove,
            onDismiss = onDismiss,
        )
    }
}

/** The sheet's body, apart from the modal so a preview can show every state of it. */
@Composable
internal fun KnownHostAddressesContent(
    addresses: KnownHostAddressesUiState,
    onInputChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TennyoBottomSheetHeader(
            title = stringResource(Res.string.connect_addresses_title),
            actionLabel = stringResource(Res.string.connect_addresses_done),
            onAction = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SHEET_MAX_HEIGHT)
                .padding(horizontal = Spacing.large),
            contentPadding = PaddingValues(top = Spacing.medium, bottom = safeDrawingBottomInset(Spacing.large)),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(Res.string.connect_addresses_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (addresses.learned.isEmpty() && addresses.added.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.connect_addresses_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            addressSection(Res.string.connect_addresses_learned_section, "learned", addresses.learned, null)
            addressSection(Res.string.connect_addresses_added_section, "added", addresses.added, onRemove)
            item(key = "input") {
                AddressInput(addresses = addresses, onInputChanged = onInputChanged, onAdd = onAdd)
            }
        }
    }
}

private fun LazyListScope.addressSection(
    title: StringResource,
    keyPrefix: String,
    endpoints: List<String>,
    onRemove: ((String) -> Unit)?,
) {
    if (endpoints.isEmpty()) return
    item(key = "$keyPrefix-title") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(items = endpoints, key = { "$keyPrefix:$it" }) { endpoint ->
        AddressRow(endpoint = endpoint, onRemove = onRemove?.let { remove -> { remove(endpoint) } })
    }
}

@Composable
private fun AddressRow(endpoint: String, onRemove: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = endpoint.substringAfter("://").substringBefore('/'),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onRemove != null) {
            TennyoButton(
                label = stringResource(Res.string.connect_addresses_remove),
                onClick = onRemove,
                style = TennyoButtonStyle.Text,
                size = TennyoButtonSize.Small,
            )
        }
    }
}

@Composable
private fun AddressInput(
    addresses: KnownHostAddressesUiState,
    onInputChanged: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        TennyoFocusedTextField(
            label = stringResource(Res.string.connect_addresses_input_label),
            value = addresses.input,
            onValueChange = onInputChanged,
            modifier = Modifier.fillMaxWidth(),
        )
        addresses.inputError?.let { error ->
            Text(
                text = stringResource(error.text()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (addresses.isChecking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                TennyoProgressIndicator()
                Text(
                    text = stringResource(Res.string.connect_addresses_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        addresses.check?.let { check ->
            Text(
                text = stringResource(check.result.text()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TennyoButton(
            label = stringResource(Res.string.connect_addresses_add),
            onClick = onAdd,
            enabled = addresses.input.isNotBlank() && !addresses.isChecking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun RemoteAddressError.text(): StringResource = when (this) {
    RemoteAddressError.EMPTY -> Res.string.connect_addresses_error_empty
    RemoteAddressError.NOT_A_HOST -> Res.string.connect_addresses_error_not_a_host
    RemoteAddressError.BAD_PORT -> Res.string.connect_addresses_error_bad_port
    RemoteAddressError.IPV6_UNSUPPORTED -> Res.string.connect_addresses_error_ipv6
    RemoteAddressError.NOT_AN_ADDRESS -> Res.string.connect_addresses_error_not_an_address
    RemoteAddressError.HOST_NAMES_UNSUPPORTED -> Res.string.connect_addresses_error_host_names
}

private fun AddressCheck.text(): StringResource = when (this) {
    AddressCheck.REACHED -> Res.string.connect_addresses_check_reached
    AddressCheck.REFUSED -> Res.string.connect_addresses_check_refused
    AddressCheck.NO_ANSWER -> Res.string.connect_addresses_check_no_answer
}

private val SHEET_MAX_HEIGHT = 520.dp
