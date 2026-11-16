package app.tennyo.mobile.design

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectAvatarStyleTest {
    @Test
    fun `same name always gets the same style`() {
        assertEquals(projectAvatarStyle("codex"), projectAvatarStyle("codex"))
    }

    @Test
    fun `names sharing a first letter get different styles`() {
        val styles = listOf("codex", "claude", "copilot").map(::projectAvatarStyle)

        assertEquals(3, styles.toSet().size)
    }

    @Test
    fun `codex keeps its pinned style across platforms`() {
        assertEquals(ProjectAvatarStyle(ProjectAvatarForm.Pentagon, 90), projectAvatarStyle("codex"))
    }

    @Test
    fun `rotation stays on the 30 degree grid`() {
        listOf("codex", "claude", "copilot", "U", "W").forEach { name ->
            assertEquals(0, projectAvatarStyle(name).rotationDegrees % 30)
        }
    }
}
