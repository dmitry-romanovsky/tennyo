package app.tennyo.host.engine.git

import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitOperationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitOutputParsersTest {
    @Test
    fun `porcelain status reads branch, counts and every kind of entry`() {
        val output = listOf(
            "# branch.oid ${"a".repeat(40)}",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 .M N... 100644 100644 100644 ${"b".repeat(40)} ${"b".repeat(40)} src/App.kt",
            "1 A. N... 000000 100644 100644 ${"0".repeat(40)} ${"c".repeat(40)} src/New file.kt",
            "1 .D N... 100644 100644 000000 ${"d".repeat(40)} ${"d".repeat(40)} old.txt",
            "2 R. N... 100644 100644 100644 ${"e".repeat(40)} ${"e".repeat(40)} R100 docs/Guide.md",
            "Guide.md",
            "2 R. N... 100644 100644 100644 ${"e".repeat(40)} ${"e".repeat(40)} R90 b.txt",
            "a.txt",
            "u UU N... 100644 100644 100644 100644 ${"f".repeat(40)} ${"f".repeat(40)} ${"f".repeat(40)} both.kt",
            "? notes/todo.md",
            "",
        ).joinToString("\u0000")

        val status = parseStatus(output)

        assertEquals("main", status.branch)
        assertEquals("a".repeat(40), status.headCommit)
        assertEquals("origin/main", status.upstream)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(
            listOf(
                StatusEntry(GitChangeKind.MODIFIED, "src/App.kt", "src/App.kt"),
                StatusEntry(GitChangeKind.ADDED, null, "src/New file.kt"),
                StatusEntry(GitChangeKind.DELETED, "old.txt", null),
                StatusEntry(GitChangeKind.MOVED, "Guide.md", "docs/Guide.md"),
                StatusEntry(GitChangeKind.RENAMED, "a.txt", "b.txt"),
                StatusEntry(GitChangeKind.CONFLICTED, "both.kt", "both.kt"),
                StatusEntry(GitChangeKind.UNTRACKED, null, "notes/todo.md"),
            ),
            status.entries,
        )
    }

    @Test
    fun `porcelain status knows an unborn branch and a detached head`() {
        val unborn = parseStatus("# branch.oid (initial)\u0000# branch.head main\u0000")
        val detached = parseStatus("# branch.oid ${"a".repeat(40)}\u0000# branch.head (detached)\u0000")

        assertNull(unborn.headCommit)
        assertEquals("main", unborn.branch)
        assertNull(detached.branch)
        assertNull(detached.upstream)
    }

    @Test
    fun `numstat keys counts by the path a change leaves and has none for binary files`() {
        val output = "3\t1\tsrc/App.kt\u0000-\t-\timage.png\u00002\t0\t\u0000old.kt\u0000new.kt\u0000"

        assertEquals(
            mapOf("src/App.kt" to (3 to 1), "image.png" to (null to null), "new.kt" to (2 to 0)),
            parseNumstat(output),
        )
    }

    @Test
    fun `name status reads a commit's files`() {
        val output = "M\u0000a.kt\u0000A\u0000b.kt\u0000D\u0000c.kt\u0000R100\u0000x/d.kt\u0000y/d.kt\u0000"

        assertEquals(
            listOf(
                StatusEntry(GitChangeKind.MODIFIED, "a.kt", "a.kt"),
                StatusEntry(GitChangeKind.ADDED, null, "b.kt"),
                StatusEntry(GitChangeKind.DELETED, "c.kt", null),
                StatusEntry(GitChangeKind.MOVED, "x/d.kt", "y/d.kt"),
            ),
            parseNameStatus(output),
        )
    }

    @Test
    fun `log records keep subjects with separators of their own`() {
        val record = listOf("a".repeat(40), "aaaaaaa", "Fix: a | b", "Ada", "10", "Grace", "20", "p1 p2")
            .joinToString("\u001f") + "\u001e\n"

        val commit = parseCommitSummaries(record).single()

        assertEquals("Fix: a | b", commit.subject)
        assertEquals(10_000L, commit.authoredAtEpochMillis)
        assertEquals(listOf("p1", "p2"), commit.parentIds)
    }

    @Test
    fun `tracking counts read either half or none`() {
        assertEquals(2 to 1, parseTrack("ahead 2, behind 1"))
        assertEquals(0 to 4, parseTrack("behind 4"))
        assertEquals(0 to 0, parseTrack(""))
        assertEquals(0 to 0, parseTrack("gone"))
    }

    @Test
    fun `remote URLs lose their credentials before reaching the phone`() {
        assertEquals("https://example.test/team/app.git", credentialFreeRemoteUrl("https://user:token@example.test/team/app.git"))
        assertEquals("ssh://example.test:22/app.git", credentialFreeRemoteUrl("ssh://git@example.test:22/app.git"))
        assertEquals("example.test:team/app.git", credentialFreeRemoteUrl("git@example.test:team/app.git"))
        assertNull(credentialFreeRemoteUrl("ext::sh -c touch% /tmp/pwned"))
        assertNull(credentialFreeRemoteUrl("file:///etc/passwd"))
        assertNull(credentialFreeRemoteUrl(""))
    }

    @Test
    fun `a failed command is classified by what Git said`() {
        fun outcome(stderr: String, stdout: String = "") =
            GitProcessResult(1, stdout.encodeToByteArray(), stderr).outcome()

        assertEquals(GitOperationOutcome.SUCCESS, GitProcessResult(0, ByteArray(0), "").outcome())
        assertEquals(GitOperationOutcome.CONFLICTS, outcome("", "CONFLICT (content): Merge conflict in a.kt"))
        assertEquals(GitOperationOutcome.NON_FAST_FORWARD, outcome("fatal: Not possible to fast-forward, aborting."))
        assertEquals(GitOperationOutcome.AUTH_FAILED, outcome("fatal: could not read Username for 'https://x'"))
        assertEquals(GitOperationOutcome.IDENTITY_MISSING, outcome("*** Please tell me who you are."))
        assertEquals(GitOperationOutcome.MISSING_UPSTREAM, outcome("There is no tracking information for the current branch."))
        assertEquals(GitOperationOutcome.REJECTED, outcome("error: failed to push some refs"))
        assertEquals(GitOperationOutcome.REJECTED, GitProcessResult(0, ByteArray(0), "", truncated = true).outcome())
    }
}
