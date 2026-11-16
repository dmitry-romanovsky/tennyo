package app.tennyo.mobile.feature.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequirementsTest {
    @Test
    fun `plain requirements are accepted`() {
        listOf(
            "Markdown==2.6.11",
            "python-frontmatter>=1.0",
            "requests[socks]~=2.31",
            "pywin32; sys_platform == \"win32\"",
            "pkg>=1.0,<2.0",
        ).forEach { assertNotNull(requirementOrNull(it), it) }
    }

    @Test
    fun `a commented file with blank lines and a byte order mark gives its requirements`() {
        val text = "﻿# tools\r\n\r\nMarkdown==2.6.11  # pinned\npython-frontmatter>=1.0\n   \n"

        val parsed = parseRequirements(text)

        assertEquals(
            listOf("Markdown==2.6.11", "python-frontmatter>=1.0"),
            assertIs<RequirementsParse.Valid>(parsed).requirements.map(Requirement::line),
        )
    }

    @Test
    fun `a file with only comments has no requirements`() {
        assertEquals(RequirementsParse.Valid(emptyList()), parseRequirements("# nothing yet\n\n"))
    }

    @Test
    fun `options are rejected`() {
        listOf("--index-url x", "-r a.txt", "-e .", "-c c.txt", "pkg==1 --index-url x").forEach { line ->
            assertNull(requirementOrNull(line), line)
            assertEquals(RequirementsParse.Rejected(2), parseRequirements("ok==1\n$line\n"), line)
        }
    }

    @Test
    fun `references to code outside the indexes are rejected`() {
        listOf("pkg @ https://x/y.whl", "git+https://x/y", "./local", "../x", "/abs").forEach { line ->
            assertNull(requirementOrNull(line), line)
        }
    }

    @Test
    fun `malformed lines are rejected`() {
        val cyrillicE = "rеquests"
        listOf("pkg\\", "pkg===1", "pkg\u0000", "pkg==1\rpkg2==1", cyrillicE, "a".repeat(201)).forEach { line ->
            assertNull(requirementOrNull(line), line)
            assertEquals(RequirementsParse.Rejected(1), parseRequirements(line), line)
        }
    }

    @Test
    fun `a file with too many requirements is rejected at the first one over the limit`() {
        val text = (1..201).joinToString("\n") { "pkg$it" }

        assertEquals(RequirementsParse.Rejected(201), parseRequirements(text))
    }
}
