package app.tennyo.host.intellij.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_MAX_ATTEMPTS
import app.tennyo.host.engine.session.ConnectionRequestStatus
import app.tennyo.host.intellij.ConnectionRequestState
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.PopupScreen
import app.tennyo.host.intellij.PopupState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import org.jetbrains.jewel.ui.component.plus

@Composable
internal fun ConnectionRequestPopupScreen(
    state: PopupState,
    request: ConnectionRequestState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = request.status == ConnectionRequestStatus.PENDING

    PopupFrame(
        state = state,
        accent = popupScreenAccent(PopupScreen.ConnectionRequest(request), state),
        statusTitle = HostBundle.message("popup.connection.request.title", request.clientName),
        statusBody = HostBundle.message("popup.request.body"),
        primary = if (pending) {
            PopupAction(text = HostBundle.message("popup.button.accept")) { onAccept(request.id) }
        } else {
            null
        },
        secondary = if (pending) {
            PopupAction(text = HostBundle.message("popup.button.reject")) { onReject(request.id) }
        } else {
            PopupAction(
                text = stoppingButtonText(state),
                enabled = !state.isStopping,
                onClick = onEnd,
            )
        },
        modifier = modifier,
    ) {
        RequestSummaryCard(request = request, modifier = Modifier.fillMaxWidth())
        PopupCallout(
            body = if (pending) {
                HostBundle.message("popup.request.hint")
            } else {
                HostBundle.message("popup.request.accepted.hint")
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ConnectionPairingPopupScreen(
    state: PopupState,
    request: ConnectionRequestState,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The budget comes from the verifier's own maximum, so the screen never promises an attempt that is gone.
    val wrongWords = state.pairingAttemptsLeft < PAIRING_CONFIRMATION_MAX_ATTEMPTS
    val accent = popupScreenAccent(PopupScreen.ConnectionPairing(request), state)

    PopupFrame(
        state = state,
        accent = accent,
        statusTitle = if (wrongWords) {
            HostBundle.message("popup.pairing.wrong.words")
        } else {
            HostBundle.message("popup.pairing.title")
        },
        statusBody = if (wrongWords) {
            HostBundle.message("popup.pairing.wrong.words.body")
        } else {
            HostBundle.message("popup.pairing.body")
        },
        primary = null,
        secondary = PopupAction(
            text = stoppingButtonText(state),
            enabled = !state.isStopping,
            onClick = onEnd,
        ),
        modifier = modifier,
        waiting = true,
    ) {
        PairingWordsIsland(
            words = request.pairingWords,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )
        PopupNote(HostBundle.message("popup.pairing.attempts.left", state.pairingAttemptsLeft))
    }
}

@Composable
private fun RequestSummaryCard(
    request: ConnectionRequestState,
    modifier: Modifier = Modifier,
) {
    PopupPanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceAvatar(deviceName = request.clientName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.verticalCommentBottomGap.dp),
            ) {
                Text(
                    text = request.clientName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = HostBundle.message(
                        "popup.connection.request.details",
                        request.clientVersion,
                        request.deviceFingerprint,
                    ),
                    color = popupMutedTextColor(),
                    fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
                )
            }
        }
    }
}

/**
 * The words the user taps on the phone, one per row and explicitly numbered.
 *
 * Tap order is part of the secret, so the ordering has to be unmistakable. A wrapping row of chips would read
 * ambiguously on a narrow popup and send the user tapping in the wrong order — which is now a hard pairing failure,
 * not a cosmetic problem. One word per line cannot wrap and cannot be misread.
 */
@Composable
private fun PairingWordsIsland(
    words: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(1.dp, accent, popupShape)
            .background(popupBackground(), popupShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEachIndexed { index, word ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(20.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = popupBackground(),
                        fontWeight = FontWeight.Bold,
                        fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
                    )
                }
                Text(
                    text = word,
                    fontWeight = FontWeight.Bold,
                    fontSize = JewelTheme.defaultTextStyle.fontSize + 6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
