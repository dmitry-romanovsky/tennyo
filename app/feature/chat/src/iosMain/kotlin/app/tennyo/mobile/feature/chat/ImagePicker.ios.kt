package app.tennyo.mobile.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.max

@Composable
internal actual fun rememberImagePicker(onPicked: (mimeType: String, base64: String) -> Unit): (() -> Unit)? {
    val picked by rememberUpdatedState(onPicked)
    // PHPickerViewController holds its delegate weakly, so the picker session lives as long as this remembered value.
    val delegate = remember { SinglePhotoDelegate { mimeType, base64 -> picked(mimeType, base64) } }
    return {
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration).also { it.delegate = delegate }
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

private class SinglePhotoDelegate(
    private val onPicked: (mimeType: String, base64: String) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        result.itemProvider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
            val jpeg = data?.let(::UIImage)?.scaledJpeg() ?: return@loadDataRepresentationForTypeIdentifier
            val base64 = jpeg.base64EncodedStringWithOptions(0u)
            dispatch_async(dispatch_get_main_queue()) { onPicked("image/jpeg", base64) }
        }
    }
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/** Same bounds as the Android picker: a JPEG with its long side at most [MAX_EDGE], small enough for one link frame. */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.scaledJpeg(): NSData? {
    val (width, height) = size.useContents { width to height }
    val scale = MAX_EDGE / max(width, height)
    if (scale >= 1.0) return UIImageJPEGRepresentation(this, JPEG_QUALITY)
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0 }
    val renderer = UIGraphicsImageRenderer(CGSizeMake(width * scale, height * scale), format)
    val scaled = renderer.imageWithActions { drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale)) }
    return UIImageJPEGRepresentation(scaled, JPEG_QUALITY)
}

private const val MAX_EDGE = 1568.0
private const val JPEG_QUALITY = 0.85
