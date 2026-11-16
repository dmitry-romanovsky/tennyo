package app.tennyo.mobile.workspace

import app.tennyo.contract.SessionStats
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStatsFormattingTest {
    @Test
    fun formatsByteCounts() {
        assertEquals("0 B", SessionStats.formatBytes(-1))
        assertEquals("512 B", SessionStats.formatBytes(512))
        assertEquals("1.0 KB", SessionStats.formatBytes(1024))
        assertEquals("1.5 KB", SessionStats.formatBytes(1536))
        assertEquals("2.0 MB", SessionStats.formatBytes(2L * 1024L * 1024L))
    }

    @Test
    fun exposesSpeedAndTotalLabels() {
        val stats = SessionStats(
            uploadBytesPerSecond = 2048,
            downloadBytesPerSecond = 4096,
            totalUploadedBytes = 1024,
            totalDownloadedBytes = 2048,
        )

        assertEquals("2.0 KB/s", stats.uploadSpeedText())
        assertEquals("4.0 KB/s", stats.downloadSpeedText())
        assertEquals("1.0 KB", stats.totalUploadText())
        assertEquals("2.0 KB", stats.totalDownloadText())
    }
}
