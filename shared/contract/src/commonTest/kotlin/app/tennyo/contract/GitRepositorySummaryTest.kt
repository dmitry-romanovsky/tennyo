package app.tennyo.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class GitRepositorySummaryTest {
    @Test
    fun `a host that does not report the location puts the repository at the project root`() {
        val summary = LinkJson.decodeFromString(GitRepositorySummary.serializer(), """{"id":"r","rootName":"app"}""")

        assertEquals("", summary.projectPath)
    }

    @Test
    fun `the location round trips and so does a repository outside the project`() {
        listOf("services/api", null).forEach { projectPath ->
            val summary = GitRepositorySummary(id = "r", rootName = "api", projectPath = projectPath)
            val encoded = LinkJson.encodeToString(GitRepositorySummary.serializer(), summary)

            assertEquals(summary, LinkJson.decodeFromString(GitRepositorySummary.serializer(), encoded))
        }
    }
}
