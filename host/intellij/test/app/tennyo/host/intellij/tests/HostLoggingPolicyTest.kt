package app.tennyo.host.intellij.tests

import app.tennyo.host.intellij.HostBuildInfo
import app.tennyo.host.intellij.HostLogLevel
import app.tennyo.host.intellij.HostLoggingPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HostLoggingPolicyTest {
    @Test
    fun `build info exposes a consistent variant logging default`() {
        if (HostBuildInfo.isDebugPluginBuild) {
            assertEquals("Tennyo Companion [debug]", HostBuildInfo.pluginDisplayName)
            assertEquals(HostLogLevel.DEBUG, HostBuildInfo.defaultLogLevel)
        } else {
            assertEquals("Tennyo Companion", HostBuildInfo.pluginDisplayName)
            assertEquals(HostLogLevel.INFO, HostBuildInfo.defaultLogLevel)
        }
    }

    @Test
    fun `verbose diagnostics are enabled only for debug logging`() {
        assertTrue(HostLoggingPolicy.allowsVerboseDiagnostics(HostLogLevel.DEBUG))
        assertFalse(HostLoggingPolicy.allowsVerboseDiagnostics(HostLogLevel.INFO))
    }
}
