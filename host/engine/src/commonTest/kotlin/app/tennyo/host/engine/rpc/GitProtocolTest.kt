package app.tennyo.host.engine.rpc

import app.tennyo.contract.GitAction
import app.tennyo.contract.GitBranch
import app.tennyo.contract.GitBranchesResponse
import app.tennyo.contract.GitChange
import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitChangeList
import app.tennyo.contract.GitCommitCapability
import app.tennyo.contract.GitCommitDetail
import app.tennyo.contract.GitCommitDetailResponse
import app.tennyo.contract.GitCommitFile
import app.tennyo.contract.GitCommitRef
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitCommitSummary
import app.tennyo.contract.GitHistoryResponse
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitOperationResult
import app.tennyo.contract.GitPushPreviewResponse
import app.tennyo.contract.GitRefKind
import app.tennyo.contract.GitRepositorySummary
import app.tennyo.contract.GitStatusResponse
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkPush
import app.tennyo.host.engine.panes.GitCommitDetailResult
import app.tennyo.host.engine.panes.GitCommitValidation
import app.tennyo.host.engine.panes.GitHistoryResult
import app.tennyo.host.engine.panes.GitMutationGuard
import app.tennyo.host.engine.panes.IdeGitPort
import app.tennyo.host.engine.panes.UnavailableGitPort
import app.tennyo.host.engine.panes.gitHistoryCursor
import app.tennyo.host.engine.panes.gitHistoryCursorOffset
import app.tennyo.host.engine.panes.resolveCountRepositoryId
import app.tennyo.host.engine.panes.validateCommitRequest
import app.tennyo.host.engine.session.FakeHostPlatform
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.testPanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GitProtocolTest {
    @Test
    fun `commit request validation distinguishes stale and unsupported input`() {
        fun validate(ids: List<String>, message: String = "Message", expectedRevision: Long = 7) =
            validateCommitRequest(GitCommitRequest("repo", ids, message, expectedRevision), 7)

        assertEquals(GitCommitValidation.Valid, validate(listOf("one")))
        assertEquals(GitCommitValidation.Unsupported, validate(emptyList()))
        assertEquals(GitCommitValidation.Stale, validate(listOf("one", "one")))
        assertEquals(GitCommitValidation.Unsupported, validate(List(501) { "id-$it" }))
        assertEquals(GitCommitValidation.Unsupported, validate(listOf("one"), "  \n"))
        assertEquals(GitCommitValidation.Unsupported, validate(listOf("one"), "x".repeat(16_001)))
        assertEquals(GitCommitValidation.Stale, validate(listOf("one"), expectedRevision = 6))
    }

    @Test
    fun `mutation guard prevents concurrent duplicate submission and releases after completion`() = runTest {
        val guard = GitMutationGuard()
        var nestedExecutions = 0
        val outer = guard.run("repo", onBusy = { "unexpected" }) {
            val nested = guard.run("repo", onBusy = { "busy" }) {
                nestedExecutions++
                "executed"
            }
            assertEquals("busy", nested)
            "complete"
        }

        assertEquals("complete", outer)
        assertEquals(0, nestedExecutions)
        assertEquals("again", guard.run("repo", onBusy = { "busy" }) { "again" })
    }

    @Test
    fun `history call keeps stale and platform failures distinct`() = runTest {
        val missing = failure { gitHistory(LinkCall.GitHistory("repository-1", limit = 10)) }
        val platform = failingGitSession(history = GitHistoryResult.PlatformFailure)
            .let { session -> failure(session) { gitHistory(LinkCall.GitHistory("repository-1", limit = 10)) } }

        assertEquals(LinkFaultCode.GIT_TARGET_STALE, missing)
        assertEquals(LinkFaultCode.GIT_PLATFORM_FAILURE, platform)
    }

    @Test
    fun `commit detail call keeps stale and platform failures distinct`() = runTest {
        val commitId = "a".repeat(40)
        val missing = failure { gitCommitDetail(LinkCall.GitCommitDetail("repository-1", commitId)) }
        val platform = failingGitSession(detail = GitCommitDetailResult.PlatformFailure)
            .let { session -> failure(session) { gitCommitDetail(LinkCall.GitCommitDetail("repository-1", commitId)) } }
        val traversal = failure { gitCommitDetail(LinkCall.GitCommitDetail("repository-1", "../HEAD")) }

        assertEquals(LinkFaultCode.GIT_TARGET_STALE, missing)
        assertEquals(LinkFaultCode.GIT_PLATFORM_FAILURE, platform)
        assertEquals(LinkFaultCode.GIT_TARGET_STALE, traversal)
    }

    @Test
    fun `count work targets only an explicit selection or sole repository`() {
        assertEquals("selected", resolveCountRepositoryId("selected", listOf("other", "selected")))
        assertEquals("sole", resolveCountRepositoryId(null, listOf("sole")))
        assertNull(resolveCountRepositoryId(null, listOf("one", "two")))
        assertNull(resolveCountRepositoryId("missing", listOf("one", "two")))
    }

    @Test
    fun `history response serializes the DTO rather than route session state`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitHistoryResponse(
                    repositoryId = "repository-1",
                    revision = 7,
                    commits = listOf(
                        GitCommitSummary(
                            id = "a".repeat(40),
                            shortId = "aaaaaaaa",
                            subject = "Fix history route",
                            authorName = "Ada",
                            authoredAtEpochMillis = 1,
                            committerName = "Ada",
                            committedAtEpochMillis = 2,
                            parentIds = emptyList(),
                        ),
                    ),
                ),
            ),
        ).jsonObject

        assertEquals("repository-1", payload["repositoryId"]?.jsonPrimitive?.content)
        assertEquals(
            "Fix history route",
            payload["commits"]!!.jsonArray.single().jsonObject["subject"]?.jsonPrimitive?.content,
        )
        assertFalse(payload.toString().contains("HostSession"))
    }

    @Test
    fun `commit contract is additive and bounded`() {
        val request = GitCommitRequest("repository-1", listOf("opaque/✓"), "Message", 7)
        val payload = Json.parseToJsonElement(hostJson(request)).jsonObject

        assertEquals("opaque/✓", payload["changeIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(setOf("repositoryId", "changeIds", "message", "expectedRevision"), payload.keys)

        val repository = GitRepositorySummary(
            id = "repository-1",
            rootName = "project",
            commitCapability = GitCommitCapability(500, 16_000),
        )
        val encoded = Json.parseToJsonElement(hostJson(repository)).jsonObject
        assertEquals("500", encoded["commitCapability"]!!.jsonObject["maxSelectedChangeCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `git status uses typed opaque ids and actions`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitStatusResponse(
                    repositories = listOf(
                        GitRepositorySummary(
                            id = "repository-1",
                            rootName = "project",
                            changeLists = listOf(
                                GitChangeList(
                                    id = "list-1",
                                    name = "Default",
                                    changes = listOf(
                                        GitChange(
                                            id = "change-1",
                                            kind = GitChangeKind.MODIFIED,
                                            fileName = "App.kt",
                                            parentPath = "src",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    selectedRepositoryId = "repository-1",
                ),
            )
        ).jsonObject

        assertEquals("repository-1", payload["selectedRepositoryId"]?.jsonPrimitive?.content)
        assertEquals("0", payload["revision"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("REFRESH", "VIEW_HISTORY", "VIEW_BRANCHES"),
            payload["repositories"]!!.jsonArray[0].jsonObject["availableActions"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(payload.toString().contains("hunks"))
        assertFalse(payload.toString().contains("/project"))
    }

    @Test
    fun `git status advertises executable mutation actions`() {
        assertEquals(
            setOf(
                GitAction.REFRESH,
                GitAction.VIEW_DIFF,
                GitAction.VIEW_HISTORY,
                GitAction.VIEW_BRANCHES,
                GitAction.PUSH,
                GitAction.CHECKOUT,
                GitAction.ROLLBACK,
                GitAction.DELETE,
            ),
            GitAction.entries.toSet(),
        )
    }

    @Test
    fun `history detail serializes its bounded file contract`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitCommitDetailResponse(
                    repositoryId = "repository-1",
                    revision = 7,
                    commit = GitCommitDetail(
                        id = "a".repeat(40), shortId = "aaaaaaaa", subject = "Add history",
                        fullMessage = "Add history\n\nExplain the graph.",
                        authorName = "Ada", authorEmail = "ada@example.test", authoredAtEpochMillis = 1234,
                        committerName = "Grace", committerEmail = "grace@example.test", committedAtEpochMillis = 2345,
                        parentIds = listOf("b".repeat(40)),
                        refs = listOf(
                            GitCommitRef(
                                id = "branch-1",
                                fullName = "main",
                                displayName = "main",
                                kind = GitRefKind.LOCAL_BRANCH,
                            ),
                        ),
                    ),
                    files = listOf(
                        GitCommitFile(
                            id = "file-1", kind = GitChangeKind.ADDED, afterPath = "src/History.kt",
                        ),
                    ),
                    filesTruncated = true,
                ),
            ),
        ).jsonObject

        assertEquals("true", payload["filesTruncated"]?.jsonPrimitive?.content)
        assertEquals("file-1", payload["files"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(
            "VIEW_DIFF",
            payload["files"]!!.jsonArray[0].jsonObject["availableActions"]!!.jsonArray.single().jsonPrimitive.content
        )
        assertEquals("Grace", payload["commit"]!!.jsonObject["committerName"]?.jsonPrimitive?.content)
        assertEquals(
            "Add history\n\nExplain the graph.",
            payload["commit"]!!.jsonObject["fullMessage"]?.jsonPrimitive?.content
        )
        assertEquals("ada@example.test", payload["commit"]!!.jsonObject["authorEmail"]?.jsonPrimitive?.content)
        assertEquals("grace@example.test", payload["commit"]!!.jsonObject["committerEmail"]?.jsonPrimitive?.content)
        assertEquals(
            "LOCAL_BRANCH",
            payload["commit"]!!.jsonObject["refs"]!!.jsonArray.single().jsonObject["kind"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "b".repeat(40),
            payload["commit"]!!.jsonObject["parentIds"]!!.jsonArray.single().jsonPrimitive.content
        )
    }

    @Test
    fun `push preview exposes a credential-free remote display URL`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitPushPreviewResponse(
                    repositoryId = "repository-1",
                    revision = 7,
                    pushTargetId = "target-1",
                    localBranch = "main",
                    upstreamBranch = "origin/main",
                    remoteName = "origin",
                    remoteDisplayUrl = "https://git.example.test/team/project.git",
                    commits = emptyList(),
                    commitsTruncated = false,
                ),
            ),
        ).jsonObject

        assertEquals(
            "https://git.example.test/team/project.git",
            payload["remoteDisplayUrl"]?.jsonPrimitive?.content,
        )
        assertFalse(payload.toString().contains("token"))
    }

    @Test
    fun `branches snapshot carries the checkout freshness revision`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitBranchesResponse(
                    repositoryId = "repository-1",
                    revision = 7,
                    localBranches = listOf(
                        GitBranch(
                            id = "local-1",
                            fullName = "main",
                            displayName = "main",
                            availableActions = setOf(GitAction.CHECKOUT),
                        ),
                    ),
                    remoteBranches = listOf(
                        GitBranch(
                            id = "remote-1",
                            fullName = "origin/main",
                            displayName = "main",
                        ),
                    ),
                    localBranchesTruncated = true,
                    remoteBranchesTruncated = true,
                ),
            ),
        ).jsonObject

        assertEquals("7", payload["revision"]?.jsonPrimitive?.content)
        assertEquals(
            "CHECKOUT",
            payload["localBranches"]!!.jsonArray.single().jsonObject["availableActions"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            0,
            payload["remoteBranches"]!!.jsonArray.single().jsonObject["availableActions"]!!.jsonArray.size,
        )
        assertEquals("true", payload["localBranchesTruncated"]?.jsonPrimitive?.content)
        assertEquals("true", payload["remoteBranchesTruncated"]?.jsonPrimitive?.content)
    }

    @Test
    fun `git invalidation exposes only lightweight recovery metadata`() {
        val payload = Json.parseToJsonElement(
            LinkJson.encodeToString(LinkPush.serializer(), LinkPush.GitInvalidated(revision = 7L)),
        ).jsonObject

        assertEquals("gitInvalidated", payload["op"]?.jsonPrimitive?.content)
        assertEquals(setOf("op", "revision"), payload.keys)
    }

    @Test
    fun `history cursor is bound to its repository and revision`() {
        val cursor = gitHistoryCursor("repository-1", revision = 7, offset = 50)

        assertEquals(50, gitHistoryCursorOffset("repository-1", revision = 7, cursor = cursor))
        assertNull(gitHistoryCursorOffset("repository-2", revision = 7, cursor = cursor))
        assertNull(gitHistoryCursorOffset("repository-1", revision = 8, cursor = cursor))
        assertNull(gitHistoryCursorOffset("repository-1", revision = 7, cursor = "not-a-cursor"))
        assertNull(
            gitHistoryCursorOffset(
                "repository-1",
                revision = 7,
                cursor = gitHistoryCursor("repository-1", 7, -1)
            )
        )
        assertNull(
            gitHistoryCursorOffset(
                "repository-1",
                revision = 7,
                cursor = gitHistoryCursor("repository-1", 7, 100_001),
            ),
        )
    }

    @Test
    fun `git operation result exposes only typed outcome data`() {
        val payload = Json.parseToJsonElement(
            hostJson(
                GitOperationResult(
                    repositoryId = "repository-1",
                    outcome = GitOperationOutcome.REJECTED,
                )
            )
        ).jsonObject

        assertEquals("REJECTED", payload["outcome"]?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("message"))
        assertFalse(payload.containsKey("commandOutput"))
    }


    private val scope = CoroutineScope(SupervisorJob())
    private val sessions = mutableListOf<SessionHost>()

    @AfterTest
    fun closeSessions() {
        sessions.forEach(SessionHost::close)
        scope.cancel()
    }

    private suspend fun failure(call: suspend HostSession.() -> Unit): LinkFaultCode? =
        failingGitSession().let { session -> failure(session, call) }

    private suspend fun failure(session: HostSession, call: suspend HostSession.() -> Unit): LinkFaultCode? =
        try {
            session.call()
            null
        } catch (fault: CallFault) {
            fault.code
        }

    private fun failingGitSession(
        history: GitHistoryResult = GitHistoryResult.MissingRepository,
        detail: GitCommitDetailResult = GitCommitDetailResult.MissingTarget,
    ): HostSession {
        val git = object : IdeGitPort by UnavailableGitPort {
            override suspend fun history(repositoryId: String, revision: Long, cursor: Int, limit: Int) = history

            override suspend fun commitDetail(repositoryId: String, revision: Long, commitId: String) = detail
        }
        val host = SessionHost(testPanes(git = git), FakeHostPlatform(), scope).also(sessions::add)
        return checkNotNull(host.session(host.startSession().sessionId))
    }
}
