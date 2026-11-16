package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import app.tennyo.mobile.design.theme.Spacing

/** How a pane sits inside the workspace scaffold: its content padding and the side padding its rows add. */
data class PanePadding(
    val contentPadding: PaddingValues,
    val bottomSafeInset: Dp,
    val contentHorizontalPadding: Dp,
)

fun panePadding(
    innerPadding: PaddingValues,
    layoutDirection: LayoutDirection,
    bottomSystemBarInset: Dp,
): PanePadding {
    val bottomSafeInset = maxOf(innerPadding.calculateBottomPadding(), bottomSystemBarInset)
    return PanePadding(
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = bottomSafeInset,
        ),
        bottomSafeInset = bottomSafeInset,
        contentHorizontalPadding = Spacing.large,
    )
}
