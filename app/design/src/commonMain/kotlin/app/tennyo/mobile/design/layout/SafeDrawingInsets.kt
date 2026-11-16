package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.Spacing

// The safe-drawing area excludes the status and navigation bars, display cutouts and the open keyboard.

@Composable
fun Modifier.safeDrawingTopPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

@Composable
fun Modifier.safeDrawingHorizontalPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

@Composable
fun safeDrawingTopInset(extra: Dp = Spacing.none): Dp =
    with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() } + extra

@Composable
fun safeDrawingBottomInset(extra: Dp = Spacing.none): Dp =
    with(LocalDensity.current) { WindowInsets.safeDrawing.getBottom(this).toDp() } + extra

/** Ends a scrolling list with room to scroll its last item out from under a floating action button. */
@Composable
fun FabClearanceSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.height(safeDrawingBottomInset(FabClearance)))
}

private val FabClearance = 96.dp
