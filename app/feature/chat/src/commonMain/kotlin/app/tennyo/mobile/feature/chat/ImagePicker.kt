package app.tennyo.mobile.feature.chat

import androidx.compose.runtime.Composable

/**
 * Opens the platform's photo picker; [onPicked] gets the picture as a MIME type and base64 data, scaled down to what
 * an agent reads. `null` where the platform has no picker the app can use.
 */
@Composable
internal expect fun rememberImagePicker(onPicked: (mimeType: String, base64: String) -> Unit): (() -> Unit)?
