package app.tennyo.mobile.feature.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitEditorPathTest {
    @Test
    fun `a repository at the project root opens the path as it is`() {
        assertEquals("src/a.kt", editorPath(repositoryPath = "", filePath = "src/a.kt"))
    }

    @Test
    fun `a nested repository prefixes its location`() {
        assertEquals("services/api/src/a.kt", editorPath("services/api", "src/a.kt"))
        assertEquals("services/api/a.kt", editorPath("services/api/", "./a.kt"))
    }

    @Test
    fun `a path that climbs out of the repository never opens`() {
        assertNull(editorPath("services/api", "../web/a.kt"))
        assertNull(editorPath("services/api", "src/../../../a.kt"))
        assertNull(editorPath("", "../a.kt"))
    }

    @Test
    fun `an absolute path or a location outside the project never opens`() {
        assertNull(editorPath("", "/etc/passwd"))
        assertNull(editorPath("", "C:/secret.txt"))
        assertNull(editorPath("../elsewhere", "a.kt"))
        assertNull(editorPath(null, "a.kt"))
        assertNull(editorPath("api", ""))
        assertNull(editorPath("", "a\\..\\..\\x"))
    }

    @Test
    fun `dots that stay inside the repository are folded away`() {
        assertEquals("api/b.kt", editorPath("api", "src/../b.kt"))
    }
}
