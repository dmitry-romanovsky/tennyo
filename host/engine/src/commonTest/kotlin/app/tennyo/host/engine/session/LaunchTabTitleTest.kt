package app.tennyo.host.engine.session

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchTabTitleTest {
    @Test
    fun `display name remains the preferred launch tab title`() {
        assertEquals(
            "Gradle: test",
            launchTabTitle(
                displayName = "Gradle: test",
                runProfileName = "test",
                configurationName = "Test Configuration",
            ),
        )
    }

    @Test
    fun `run profile name replaces the internal configuration name fallback`() {
        assertEquals(
            "Test Configuration",
            launchTabTitle(
                displayName = null,
                runProfileName = "Test Configuration",
                configurationName = "Fallback Configuration",
            ),
        )
    }

    @Test
    fun `configuration name and default title stay as secondary fallbacks`() {
        assertEquals(
            "Fallback Configuration",
            launchTabTitle(
                displayName = null,
                runProfileName = null,
                configurationName = "Fallback Configuration",
            ),
        )
        assertEquals(
            "Launch",
            launchTabTitle(
                displayName = null,
                runProfileName = null,
                configurationName = null,
            ),
        )
    }
}
