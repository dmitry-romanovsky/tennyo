package app.tennyo.mobile.feature.git

import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitOperationResult
import app.tennyo.contract.GitProjectInfoResponse
import app.tennyo.contract.GitRollbackRequest
import app.tennyo.contract.GitStatusResponse
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkJson
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.LinkFault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeGitSourceTest {
    private val calls = mutableListOf<LinkCall>()

    @Test
    fun aStatusFromAnOlderHostWithoutCommitLimitsCannotBeCommittedTo(): Unit = runBlocking {
        val source = sourceAnswering(
            LinkResult.GitStatus(
                LinkJson.decodeFromString(
                    GitStatusResponse.serializer(),
                    """{"repositories":[{"id":"repo","rootName":"Repo","rootPath":"/repo"}],"revision":4}""",
                ),
            ),
        )

        assertNull(source.status(repositoryId = null).repositories.single().commitCapability)
    }

    @Test
    fun theHostsFaultCodeBecomesTheRejectionWithoutReadingItsMessage(): Unit = runBlocking {
        val source = sourceFailing(LinkFaultCode.GIT_TARGET_STALE, "localized text that may change")

        val fault = assertFailsWith<GitFault.Rejected> { source.branches("repo") }

        assertEquals(GitRejection.STALE_TARGET, fault.reason)
    }

    @Test
    fun commitJsonContainsOnlyOpaqueIdsMessageRepositoryAndRevision() {
        val body = LinkJson.encodeToString(
            GitCommitRequest.serializer(),
            GitCommitRequest("repo", listOf("a/b?c#✓", "空 白"), "Keep\ntext", 7),
        )
        val payload = LinkJson.parseToJsonElement(body).jsonObject

        assertEquals(setOf("repositoryId", "changeIds", "message", "expectedRevision"), payload.keys)
        assertFalse(body.contains("path", ignoreCase = true))
        assertFalse(body.contains("amend", ignoreCase = true))
        assertFalse(body.contains("push", ignoreCase = true))
        assertTrue(body.contains("a/b?c#✓"))
        assertTrue(body.contains("空 白"))
    }

    @Test
    fun committingSendsOneCallAndDoesNotRetryIt(): Unit = runBlocking {
        val source = sourceFailing(LinkFaultCode.GIT_PLATFORM_FAILURE, "failed")

        val fault = assertFailsWith<GitFault.Rejected> {
            source.commit("repo", GitCommitSelection(listOf("id/✓"), expectedRevision = 9), "Message")
        }

        assertEquals(GitRejection.PLATFORM_FAILURE, fault.reason)
        assertEquals(
            LinkCall.GitCommit(GitCommitRequest("repo", listOf("id/✓"), "Message", 9)),
            calls.single(),
        )
    }

    @Test
    fun aPullThatCannotFastForwardIsTheFaultThatOffersAMerge(): Unit = runBlocking {
        val source = sourceAnswering(
            LinkResult.GitOperation(GitOperationResult("repo", GitOperationOutcome.NON_FAST_FORWARD)),
        )

        assertFailsWith<GitFault.NonFastForward> { source.pull("repo") }
        assertEquals(LinkCall.GitPull("repo"), calls.single())
        assertTrue(source.capabilities.pull && source.capabilities.projectSettings)
        assertFalse(source.capabilities.credentials || source.capabilities.identity)
    }

    @Test
    fun rollbackSendsTheChosenIdsAndRevision(): Unit = runBlocking {
        val source = sourceAnswering(LinkResult.GitOperation(GitOperationResult("repo", GitOperationOutcome.SUCCESS)))

        val result = source.rollback("repo", GitCommitSelection(listOf("x"), expectedRevision = 3))

        assertEquals(GitOutcome.SUCCESS, result.outcome)
        assertEquals(LinkCall.GitRollback(GitRollbackRequest("repo", listOf("x"), 3)), calls.single())
    }

    @Test
    fun projectInfoKeepsAnAuthorOnlyWhenBothHalvesAreSet(): Unit = runBlocking {
        val source = sourceAnswering(
            LinkResult.GitProjectInfo(
                GitProjectInfoResponse(
                    repositoryId = "repo",
                    location = "/work/app",
                    identityName = "Ada",
                    remotes = emptyList(),
                    stashCount = 0,
                    tagCount = 0,
                ),
            ),
        )

        assertNull(source.projectInfo("repo").identity)
    }

    private fun sourceAnswering(result: LinkResult): IdeGitSource = IdeGitSource(recording { result })

    private fun sourceFailing(fault: LinkFaultCode, message: String): IdeGitSource =
        IdeGitSource(recording { throw LinkFault.fromLink(fault, message) })

    private fun recording(answer: (LinkCall) -> LinkResult): LinkCalls =
        object : LinkCalls {
            override suspend fun call(call: LinkCall): LinkResult {
                calls += call
                return answer(call)
            }
        }
}
