package app.tennyo.host.intellij.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.RememberedDeviceState
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import kotlin.time.Duration.Companion.milliseconds

/**
 * The phones this computer remembers, each of which reconnects without the pairing words until its pair expires.
 * Always listed on the main screen, so what can walk back in is never one click away from view.
 */
@Composable
internal fun KnownDevicesSection(
    devices: List<RememberedDeviceState>,
    /** Visibility "No one" keeps every remembered phone out; the list says so instead of looking usable. */
    blocked: Boolean,
    onForget: (pairId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
        PopupSectionTitle(HostBundle.message("popup.known.devices.title"))
        if (devices.isEmpty()) {
            PopupNote(HostBundle.message("popup.known.devices.empty"))
            return@Column
        }
        if (blocked) {
            PopupNote(HostBundle.message("popup.known.devices.blocked"))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = knownDevicesMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp),
        ) {
            devices.forEach { device ->
                key(device.pairId) {
                    KnownDeviceRow(
                        device = device,
                        onForget = { onForget(device.pairId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * One remembered phone: its name, platform, when it last connected and when this computer forgets it. Forgetting
 * takes a second click on the same button within [FORGET_CONFIRM_MILLIS], since a forgotten phone has to pair again.
 */
@Composable
private fun KnownDeviceRow(
    device: RememberedDeviceState,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember(device.pairId) { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(FORGET_CONFIRM_MILLIS.milliseconds)
            confirming = false
        }
    }
    val details = buildList {
        device.deviceOs?.let { os -> add(listOfNotNull(os, device.deviceOsVersion).joinToString(" ")) }
        device.lastUsedAtEpochMillis.takeIf { it > 0 }?.let { add(lastUsedText(it)) }
        add(
            if (device.hoursLeft >= 2 * HOURS_PER_DAY) {
                HostBundle.message("popup.known.devices.expires.days", device.hoursLeft / HOURS_PER_DAY)
            } else {
                HostBundle.message("popup.known.devices.expires", device.hoursLeft)
            },
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.deviceName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details.joinToString(" · "),
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
            )
        }
        PopupButton(
            text = HostBundle.message(
                if (confirming) "popup.known.devices.forget.confirm" else "popup.known.devices.forget",
            ),
            icon = null,
            style = if (confirming) PopupButtonStyle.Danger else PopupButtonStyle.Quiet,
            size = PopupButtonSize.Small,
            onClick = { if (confirming) onForget() else confirming = true },
        )
    }
}

/** When the phone last connected, to the nearest minute, hour or day. */
private fun lastUsedText(lastUsedAtEpochMillis: Long): String {
    val minutes = ((System.currentTimeMillis() - lastUsedAtEpochMillis) / MILLIS_PER_MINUTE).coerceAtLeast(0)
    return when {
        minutes < 1 -> HostBundle.message("popup.known.devices.used.now")
        minutes < MINUTES_PER_HOUR -> HostBundle.message("popup.known.devices.used.minutes", minutes)
        minutes < MINUTES_PER_DAY ->
            HostBundle.message("popup.known.devices.used.hours", minutes / MINUTES_PER_HOUR)
        else -> HostBundle.message("popup.known.devices.used.days", minutes / MINUTES_PER_DAY)
    }
}

private val knownDevicesMaxHeight = 168.dp
private const val FORGET_CONFIRM_MILLIS = 3_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * 60L
private const val HOURS_PER_DAY = 24L
