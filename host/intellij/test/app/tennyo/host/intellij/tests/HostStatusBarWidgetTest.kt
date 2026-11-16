package app.tennyo.host.intellij.tests

import app.tennyo.host.intellij.HostStatusBarWidget
import app.tennyo.host.intellij.HostStatusBarWidgetFactory
import app.tennyo.host.intellij.HostStatusIndication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HostStatusBarWidgetTest {
    @Test
    fun `the factory id matches the widget the status bar looks up`() {
        assertEquals(HostStatusBarWidget.WIDGET_ID, HostStatusBarWidgetFactory().id)
    }

    @Test
    fun `the widget names itself from the bundle`() {
        assertTrue(HostStatusBarWidgetFactory().displayName.isNotBlank())
    }

    @Test
    fun `every session state has an icon and a description`() {
        HostStatusIndication.entries.forEach { indication ->
            assertNotNull(indication.icon, "${indication.name} has no icon")
            assertTrue(indication.description.isNotBlank(), "${indication.name} has no description")
        }
    }
}
