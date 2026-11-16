package app.tennyo.host.intellij.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_MAX_ATTEMPTS
import app.tennyo.host.intellij.PopupScreen
import app.tennyo.host.intellij.PopupState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import org.jetbrains.jewel.ui.component.plus

/**
 * The one frame every popup state wears: the companion header, a status line whose dot names the state by colour, a
 * body of fixed height, and an action bar.
 *
 * The body's floor is what keeps the popup from resizing under the pointer as the session moves between states —
 * the window is anchored to a toolbar button or a status bar widget, and a height that changed on every push would
 * drag the whole popup across the screen.
 */
@Composable
internal fun PopupFrame(
    state: PopupState,
    accent: Color,
    statusTitle: String,
    statusBody: String,
    primary: PopupAction?,
    secondary: PopupAction?,
    modifier: Modifier = Modifier,
    /** The popup is waiting on the phone; the status dot becomes the moving Tennyo mark. */
    waiting: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.background(popupPalette().surface)) {
        PopupHeader(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = popupFrameHorizontalPadding, vertical = 9.dp),
        )
        DividerLine()
        PopupStatusObject(
            accent = accent,
            waiting = waiting,
            title = statusTitle,
            body = statusBody,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = popupFrameHorizontalPadding,
                    end = popupFrameHorizontalPadding,
                    top = 16.dp,
                    bottom = 12.dp,
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = popupBodyMinHeight)
                .padding(
                    start = popupFrameHorizontalPadding,
                    end = popupFrameHorizontalPadding,
                    top = 4.dp,
                    bottom = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.verticalMediumGap.dp),
            content = body,
        )
        if (primary != null || secondary != null) {
            DividerLine()
            PopupActionBar(
                primary = primary,
                secondary = secondary,
                modifier = Modifier
                    .fillMaxWidth()
                        .padding(horizontal = popupFrameHorizontalPadding, vertical = 12.dp),
            )
        }
    }
}

/** The dot, title and one supporting line that answer "where am I" without reading the rest of the popup. */
@Composable
private fun PopupStatusObject(
    accent: Color,
    waiting: Boolean,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (waiting) {
            OrbitIndicator(color = accent, indicatorSize = popupStatusOrbitSize)
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(popupStatusDotSize)
                    .background(accent, CircleShape),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.verticalCommentBottomGap.dp)) {
            Text(
                text = title,
                color = popupTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize + 3.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = popupMutedTextColor(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            )
        }
    }
}

/** The action bar of design 2b: one filled commitment, one quiet way out. */
@Composable
private fun PopupActionBar(
    primary: PopupAction?,
    secondary: PopupAction?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (primary != null) {
            PopupButton(
                text = primary.text,
                icon = null,
                modifier = Modifier.weight(1f),
                enabled = primary.enabled,
                style = PopupButtonStyle.Primary,
                size = PopupButtonSize.Large,
                onClick = primary.onClick,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (secondary != null) {
            PopupButton(
                text = secondary.text,
                icon = null,
                enabled = secondary.enabled,
                style = PopupButtonStyle.Quiet,
                size = PopupButtonSize.Large,
                onClick = secondary.onClick,
            )
        }
    }
}

internal data class PopupAction(
    val text: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** The colour that names the state on the status dot. */
@Composable
internal fun popupScreenAccent(screen: PopupScreen, state: PopupState): Color =
    when (screen) {
        PopupScreen.Inactive -> if (state.pairingWindow != null) popupWaitingAccent() else popupPalette().outline
        is PopupScreen.ConnectionRequest -> popupWarningAccent()
        is PopupScreen.ConnectionPairing ->
            if (state.pairingAttemptsLeft < PAIRING_CONFIRMATION_MAX_ATTEMPTS) popupDangerAccent()
            else popupReadyAccent()

        PopupScreen.Connected -> popupReadyAccent()
    }

private val popupStatusDotSize = 9.dp
private val popupStatusOrbitSize = 22.dp
private val popupFrameHorizontalPadding = 14.dp
private val popupBodyMinHeight = 258.dp
