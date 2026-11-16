package app.tennyo.mobile.design.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.layout.safeDrawingTopInset

@Composable
internal actual fun fileNavigatorTopInset(): Dp = safeDrawingTopInset()

@Composable
internal actual fun fileNavigatorBottomInset(): Dp = safeDrawingBottomInset()
