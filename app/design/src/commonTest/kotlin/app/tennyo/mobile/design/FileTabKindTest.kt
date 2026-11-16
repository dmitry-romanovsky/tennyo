package app.tennyo.mobile.design

import kotlin.test.Test
import kotlin.test.assertEquals

class FileTabKindTest {
    @Test
    fun `file tab icons follow the content type`() {
        assertEquals(FileTabKind.SOURCE, fileTabKind("UserService.kt"))
        assertEquals(FileTabKind.SOURCE, fileTabKind("main.py"))
        assertEquals(FileTabKind.CONFIGURATION, fileTabKind("build.gradle.kts"))
        assertEquals(FileTabKind.CONFIGURATION, fileTabKind("package.json"))
        assertEquals(FileTabKind.DOCUMENT, fileTabKind("README.md"))
        assertEquals(FileTabKind.OTHER, fileTabKind("Makefile"))
        assertEquals(FileTabKind.OTHER, fileTabKind("logo.png"))
    }
}
