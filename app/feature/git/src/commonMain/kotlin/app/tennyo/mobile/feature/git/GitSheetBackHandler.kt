package app.tennyo.mobile.feature.git

import androidx.compose.runtime.Composable

/**
 * System back inside the Git sheet while [enabled]. Android's back reaches the sheet's own window first, so a handler
 * composed inside it wins over the sheet's dismissal; iOS has no system back there.
 */
@Composable
internal expect fun GitSheetBackHandler(enabled: Boolean, onBack: () -> Unit)
