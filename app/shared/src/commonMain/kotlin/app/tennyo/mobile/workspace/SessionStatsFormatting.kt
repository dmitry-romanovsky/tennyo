package app.tennyo.mobile.workspace

import app.tennyo.contract.SessionStats

private const val BYTES_PER_UNIT = 1024.0
private const val ONE_DECIMAL_PLACE = 10.0

/** Display-string formatting helpers for [SessionStats], kept client-side as presentation logic. */
fun SessionStats.uploadSpeedText(): String = "${SessionStats.formatBytes(uploadBytesPerSecond)}/s"

fun SessionStats.downloadSpeedText(): String = "${SessionStats.formatBytes(downloadBytesPerSecond)}/s"

fun SessionStats.totalUploadText(): String = SessionStats.formatBytes(totalUploadedBytes)

fun SessionStats.totalDownloadText(): String = SessionStats.formatBytes(totalDownloadedBytes)

fun SessionStats.Companion.formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= BYTES_PER_UNIT && unitIndex < units.lastIndex) {
        value /= BYTES_PER_UNIT
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        val rounded = kotlin.math.round(value * ONE_DECIMAL_PLACE) / ONE_DECIMAL_PLACE
        "$rounded ${units[unitIndex]}"
    }
}
