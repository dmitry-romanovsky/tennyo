package app.tennyo.mobile.feature.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalPackagesReduceTest {
    private val resolved = PackageResolution.Resolved(listOf(ResolvedPackage("pyyaml", "6.0.3", PackageIndex.CHAQUOPY)))
    private val record = PackagesRecord(requirements = emptyList(), added = listOf("python-frontmatter"))
    private val source = PackageSource.MissingModule("frontmatter")
    private val asked = reduce(
        LocalWorkspaceState("demo"),
        LocalPackagesMsg.InstallAsked(1, PackageSource.MissingModule("frontmatter"), thenRun = "main.py"),
    )

    @Test
    fun `an install goes from asking to resolving to reviewing to installing to done`() {
        assertIs<PackageInstall.Asking>(asked.packages)

        val resolving = reduce(asked, LocalPackagesMsg.InstallConfirmed(1))
        assertIs<PackageInstall.Resolving>(resolving.packages)

        val reviewing = reduce(resolving, LocalPackagesMsg.Resolved(1, resolved, record))
        assertEquals(PackageInstall.Reviewing(1, "main.py", source, resolved, record), reviewing.packages)

        val installing = reduce(reviewing, LocalPackagesMsg.InstallReviewed(1))
        assertEquals(PackageInstall.Installing(1, "main.py", source), installing.packages)

        assertNull(reduce(installing, LocalPackagesMsg.InstallFinished(1, failure = null)).packages)
    }

    @Test
    fun `a failed lookup and a failed install end in a typed failure`() {
        val resolving = reduce(asked, LocalPackagesMsg.InstallConfirmed(1))

        val lookup = reduce(
            resolving,
            LocalPackagesMsg.Resolved(1, PackageResolution.Failed(PackageFailure.NOT_FOUND, "nope"), record),
        )
        assertEquals(PackageInstall.Failed(1, "main.py", source, PackageFailure.NOT_FOUND, "nope"), lookup.packages)

        val reviewing = reduce(resolving, LocalPackagesMsg.Resolved(1, resolved, record))
        val installing = reduce(reviewing, LocalPackagesMsg.InstallReviewed(1))
        val failed = reduce(installing, LocalPackagesMsg.InstallFinished(1, PackageResolution.Failed(PackageFailure.REJECTED, "bad wheel")))
        assertEquals(PackageInstall.Failed(1, "main.py", source, PackageFailure.REJECTED, "bad wheel"), failed.packages)
    }

    @Test
    fun `an answer for an install the user dismissed is dropped`() {
        val resolving = reduce(asked, LocalPackagesMsg.InstallConfirmed(1))
        val dismissed = reduce(resolving, LocalPackagesMsg.InstallDismissed)
        assertNull(dismissed.packages)

        assertEquals(dismissed, reduce(dismissed, LocalPackagesMsg.Resolved(1, resolved, record)))
        assertEquals(dismissed, reduce(dismissed, LocalPackagesMsg.InstallFinished(1, failure = null)))
    }

    @Test
    fun `an answer for an earlier install does not touch a newer one`() {
        val newer = reduce(
            reduce(asked, LocalPackagesMsg.InstallDismissed),
            LocalPackagesMsg.InstallAsked(2, PackageSource.MissingModule("yaml"), thenRun = null),
        )
        val resolving = reduce(newer, LocalPackagesMsg.InstallConfirmed(2))

        assertEquals(resolving, reduce(resolving, LocalPackagesMsg.Resolved(1, resolved, record)))
    }

    @Test
    fun `run is disabled while an install is under way`() {
        val installing = PackageInstall.Installing(1, thenRun = null, source = source)

        assertTrue(LocalWorkspaceState("demo").canRun)
        assertFalse(LocalWorkspaceState("demo", packages = PackageInstall.Resolving(1, null, source)).canRun)
        assertFalse(LocalWorkspaceState("demo", packages = installing).canRun)
    }

    @Test
    fun `a second ask while a step is showing is dropped`() {
        val again = reduce(asked, LocalPackagesMsg.InstallAsked(2, PackageSource.MissingModule("yaml"), thenRun = null))

        assertEquals(asked, again)
    }

    @Test
    fun `declining the requirements prompt is remembered but declining a missing module is not`() {
        val requirements = listOf("Markdown==2.6.11").mapNotNull(::requirementOrNull)
        val fromFile = reduce(
            LocalWorkspaceState("demo"),
            LocalPackagesMsg.InstallAsked(1, PackageSource.RequirementsFile(requirements), thenRun = "main.py"),
        )

        assertEquals(listOf("Markdown==2.6.11"), reduce(fromFile, LocalPackagesMsg.InstallDismissed).declinedRequirements)
        assertNull(reduce(asked, LocalPackagesMsg.InstallDismissed).declinedRequirements)
    }
}
