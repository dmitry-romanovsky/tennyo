package app.tennyo.host.engine.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectPathTest {
    @Test
    fun `a repository at the project root has an empty location`() {
        assertEquals("", projectPathOf("/work/app", "/work/app"))
        assertEquals("", projectPathOf("/work/app/", "/work/app"))
    }

    @Test
    fun `a nested repository is located below the project`() {
        assertEquals("services/api", projectPathOf("/work/app", "/work/app/services/api"))
    }

    @Test
    fun `a repository outside the project folder has no location`() {
        assertNull(projectPathOf("/work/app", "/work"))
        assertNull(projectPathOf("/work/app", "/work/app-two"))
        assertNull(projectPathOf(null, "/work/app"))
    }
}
