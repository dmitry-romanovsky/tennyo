package app.tennyo.mobile.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

@Composable
internal actual fun rememberImagePicker(onPicked: (mimeType: String, base64: String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: return@rememberLauncherForActivityResult
        picked("image/jpeg", Base64.encodeToString(bitmap.scaledJpeg(), Base64.NO_WRAP))
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * The picture as a JPEG no longer than [MAX_EDGE] on its long side: what vision models read anyway, and small enough
 * to travel in one link frame.
 */
private fun Bitmap.scaledJpeg(): ByteArray {
    val scale = MAX_EDGE.toFloat() / max(width, height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else {
        this
    }
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
}

private const val MAX_EDGE = 1568
private const val JPEG_QUALITY = 85
