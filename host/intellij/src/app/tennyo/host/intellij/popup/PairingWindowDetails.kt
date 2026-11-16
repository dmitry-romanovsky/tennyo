package app.tennyo.host.intellij.popup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.PairingWindowState
import app.tennyo.host.intellij.currentAppName
import app.tennyo.host.intellij.currentExecutablePath
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import org.jetbrains.jewel.ui.component.plus
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.skiko.hostOs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * What "All" adds to the main screen while its pairing window is open: the name and id the phone picks this computer
 * by, how long it stays visible, and help for a phone that cannot find it.
 */
@Composable
internal fun PairingWindowDetails(
    window: PairingWindowState,
    connectionHelpExpanded: Boolean,
    onToggleConnectionHelp: () -> Unit,
    onCopyExecutablePath: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.verticalMediumGap.dp)) {
        PairingWindowCard(window = window, modifier = Modifier.fillMaxWidth())
        ConnectionHelp(
            address = window.address,
            expanded = connectionHelpExpanded,
            onToggleExpanded = onToggleConnectionHelp,
            onCopyExecutablePath = onCopyExecutablePath,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The name the phone picks this computer by, and how long it stays visible.
 *
 * The name is drawn fresh for every window and means nothing on its own; the id beneath it is derived from this
 * host's transport fingerprint, so it is the part the user can compare against what their phone shows.
 */
@Composable
private fun PairingWindowCard(
    window: PairingWindowState,
    modifier: Modifier = Modifier,
) {
    PopupPanel(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.verticalCommentBottomGap.dp),
        ) {
            Text(
                text = window.hostName,
                fontWeight = FontWeight.Bold,
                fontSize = JewelTheme.defaultTextStyle.fontSize + 8.sp,
            )
            Text(
                text = window.displayShortId,
                fontFamily = FontFamily.Monospace,
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
            )
            PairingWindowCountdown(
                expiresAtEpochMillis = window.expiresAtEpochMillis,
                modifier = Modifier.padding(top = spacing.verticalSmallGap.dp),
            )
        }
    }
}

/**
 * Two checks for a phone that does not list this computer: the network, then the one OS gate that blocks it. Folded
 * by default, since most phones find the computer straight away.
 */
@Composable
private fun ConnectionHelp(
    address: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopyExecutablePath: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
        Row(
            modifier = Modifier.clickable(onClick = onToggleExpanded),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = popupMutedTextColor(),
            )
            Text(
                text = HostBundle.message("popup.connection.help.title"),
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = helpStepIndent),
                verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp),
            ) {
                HelpStep(
                    number = 1,
                    text = address?.let { HostBundle.message("popup.connection.help.network.address", it) }
                        ?: HostBundle.message("popup.connection.help.network"),
                )
                HelpStep(number = 2, text = firewallHelpText())
                if (hostOs.isWindows) {
                    val executablePath = remember { currentExecutablePath() }
                    Text(
                        text = executablePath,
                        fontFamily = FontFamily.Monospace,
                        color = popupMutedTextColor(),
                        fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
                        modifier = Modifier.padding(start = helpStepIndent),
                    )
                    PopupCopyButton(
                        text = HostBundle.message("popup.button.copy.ide.path"),
                        onClick = onCopyExecutablePath,
                        modifier = Modifier.padding(start = helpStepIndent),
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$number.",
            color = popupMutedTextColor(),
            fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            modifier = Modifier.width(helpStepIndent),
        )
        Text(
            text = text,
            fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun firewallHelpText(): String = when {
    hostOs.isMacOS -> HostBundle.message("popup.connection.help.macos", currentAppName())
    hostOs.isWindows -> HostBundle.message("popup.connection.help.windows")
    else -> HostBundle.message("popup.connection.help.linux")
}

/** Counts the window down once a second, so the user can see it running out rather than discovering it has. */
@Composable
private fun PairingWindowCountdown(expiresAtEpochMillis: Long, modifier: Modifier = Modifier) {
    var remainingSeconds by remember(expiresAtEpochMillis) {
        mutableStateOf(remainingSeconds(expiresAtEpochMillis))
    }
    LaunchedEffect(expiresAtEpochMillis) {
        while (remainingSeconds > 0) {
            delay(1.seconds)
            remainingSeconds = remainingSeconds(expiresAtEpochMillis)
        }
    }
    Text(
        text = if (remainingSeconds > 0) {
            HostBundle.message("popup.waiting.visible.for", formatRemaining(remainingSeconds))
        } else {
            HostBundle.message("popup.waiting.expired")
        },
        modifier = modifier,
        color = popupWaitingAccent(),
        fontWeight = FontWeight.SemiBold,
        fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
    )
}

private fun remainingSeconds(expiresAtEpochMillis: Long): Long =
    ((expiresAtEpochMillis - Clock.System.now().toEpochMilliseconds()) / MILLIS_PER_SECOND).coerceAtLeast(0)

private fun formatRemaining(totalSeconds: Long): String =
    "${totalSeconds / SECONDS_PER_MINUTE}:${(totalSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

private val helpStepIndent = 16.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
