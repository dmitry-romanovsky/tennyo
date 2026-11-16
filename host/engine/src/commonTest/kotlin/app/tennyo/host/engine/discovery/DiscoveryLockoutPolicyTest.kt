package app.tennyo.host.engine.discovery

import app.tennyo.contract.ProjectChoice
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.session.latestSuccessfulRemoteConnectionAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryLockoutPolicyTest {
    private val store = object : HostSettingsStore {
        override var trust = DiscoveryTrustState()
    }
    private val policy = DiscoveryLockoutPolicy(store)

    @Test
    fun `discovery descriptor timestamp comes from successful remembered connections`() {
        assertEquals(
            20L,
            latestSuccessfulRemoteConnectionAt(
                companionId = "companion",
                pairs = listOf(
                    RememberedPairSummary("one", "Phone", "companion", "Project", "2026.2", 10L, null, 100L),
                    RememberedPairSummary("two", "Tablet", "companion", "Project", "2026.2", 20L, null, 100L),
                    RememberedPairSummary(
                        "stale",
                        "Paired phone",
                        "other-companion",
                        "Project",
                        "2026.2",
                        30L,
                        null,
                        100L,
                    ),
                ),
            ),
        )
        assertEquals(0L, latestSuccessfulRemoteConnectionAt("companion", emptyList()))
    }

    @Test
    fun `companion identity recovery keeps durable store ahead of xml and remembered pairs`() {
        val resolved = recoverCompanionIdentity(
            stateCompanionId = "xml-companion",
            durableCompanionId = "durable-companion",
            createIfMissing = true,
            newCompanionId = { "generated-companion" },
        )

        assertEquals("durable-companion", resolved?.companionId)
        assertEquals(HostIdentitySource.DurableStore, resolved?.source)
    }

    @Test
    fun `companion identity recovery falls back to xml state when durable identity is missing`() {
        val recovered = recoverCompanionIdentity(
            stateCompanionId = "xml-companion",
            durableCompanionId = null,
            createIfMissing = true,
            newCompanionId = { "generated-companion" },
        )

        assertEquals("xml-companion", recovered?.companionId)
        assertEquals(HostIdentitySource.GlobalSettingsState, recovered?.source)
    }

    @Test
    fun `companion identity recovery generates a new id only when no source survives`() {
        val recovered = recoverCompanionIdentity(
            stateCompanionId = "",
            durableCompanionId = null,
            createIfMissing = true,
            newCompanionId = { "generated-companion" },
        )

        assertEquals("generated-companion", recovered?.companionId)
        assertEquals(HostIdentitySource.Generated, recovered?.source)
        assertNull(recoverCompanionIdentity(stateCompanionId = "", durableCompanionId = null, createIfMissing = false))
    }

    @Test
    fun `discovery connect keeps using the discover snapshot when only one project was originally available`() {
        val selectedProjectId = selectDiscoveryProjectId(
            requestedProjectId = null,
            projectChoices = listOf(ProjectChoice(projectId = "owner-project", projectName = "Owner", projectPath = "/tmp/owner")),
        )

        assertEquals("owner-project", selectedProjectId)
    }

    @Test
    fun `discovery connect rejects a project that was not offered during discover`() {
        val selectedProjectId = selectDiscoveryProjectId(
            requestedProjectId = "new-project",
            projectChoices = listOf(ProjectChoice(projectId = "owner-project", projectName = "Owner", projectPath = "/tmp/owner")),
        )

        assertNull(selectedProjectId)
    }

    @Test
    fun `discovery advertisement becomes available again after the last session stops`() {
        val ownerProjectId = DiscoveryLockoutPolicy.resolveDiscoveryAdvertisementOwnerProjectId(
            enabled = true,
            preferredOwnerProjectId = "owner-project",
            registeredProjectIds = listOf("owner-project", "second-project"),
            activeSessionOwnerProjectIds = setOf("owner-project"),
        )
        val restartedOwnerProjectId = DiscoveryLockoutPolicy.resolveDiscoveryAdvertisementOwnerProjectId(
            enabled = true,
            preferredOwnerProjectId = "owner-project",
            registeredProjectIds = listOf("owner-project", "second-project"),
            activeSessionOwnerProjectIds = emptySet(),
        )

        assertNull(ownerProjectId)
        assertEquals("owner-project", restartedOwnerProjectId)
    }

    @Test
    fun `discovery advertisement falls back to another registered project when the preferred owner is gone`() {
        val advertisingOwnerProjectId = DiscoveryLockoutPolicy.resolveDiscoveryAdvertisementOwnerProjectId(
            enabled = true,
            preferredOwnerProjectId = "closed-owner-project",
            registeredProjectIds = listOf("open-project", "second-project"),
            activeSessionOwnerProjectIds = emptySet(),
        )

        assertEquals("open-project", advertisingOwnerProjectId)
    }

    @Test
    fun `discovery connect selects the second project when the user requests it`() {
        val selectedProjectId = selectDiscoveryProjectId(
            requestedProjectId = "second-project",
            projectChoices = listOf(
                ProjectChoice(projectId = "owner-project", projectName = "Owner", projectPath = "/tmp/owner"),
                ProjectChoice(projectId = "second-project", projectName = "Second", projectPath = "/tmp/second"),
            ),
        )

        assertEquals("second-project", selectedProjectId)
    }

    @Test
    fun `available project ordering keeps the active owner first without hiding other projects`() {
        val orderedProjects = DiscoveryLockoutPolicy.orderDiscoveryProjectChoices(
            preferredOwnerProjectId = "owner-project",
            projectChoices = listOf(
                ProjectChoice(projectId = "second-project", projectName = "Second", projectPath = "/tmp/second"),
                ProjectChoice(projectId = "owner-project", projectName = "Owner", projectPath = "/tmp/owner"),
            ),
        )

        assertEquals(listOf("owner-project", "second-project"), orderedProjects.map { it.projectId })
    }

    @Test
    fun `the fifth failure within the window blocks discovery with its message`() {
        policy.setEnabled(ownerProjectId = "owner", enabled = true)

        repeat(DiscoveryLockoutPolicy.MAX_FAILURES - 1) { attempt ->
            assertFalse(policy.recordSecurityFailure("failure $attempt", "10.0.0.9", nowEpochMillis = 1_000L + attempt))
        }
        assertTrue(policy.isEnabled())

        assertTrue(policy.recordSecurityFailure("last failure", "10.0.0.9", nowEpochMillis = 2_000L))
        assertFalse(policy.isEnabled())
        assertEquals("last failure", policy.blockedMessage())
        assertEquals(2_000L, store.trust.blockedAtEpochMillis)
        assertEquals("10.0.0.9", store.trust.lastAttackerSource)
    }

    @Test
    fun `failures older than the window no longer count`() {
        repeat(DiscoveryLockoutPolicy.MAX_FAILURES - 1) { attempt ->
            policy.recordSecurityFailure("failure", null, nowEpochMillis = attempt.toLong())
        }

        val later = DiscoveryLockoutPolicy.FAILURE_WINDOW_MILLIS + DiscoveryLockoutPolicy.MAX_FAILURES
        assertFalse(policy.recordSecurityFailure("failure", null, nowEpochMillis = later))
        assertEquals(1, store.trust.countedFailureEpochMillis.size)
    }

    @Test
    fun `resetting trust clears the block and the count but keeps the owner`() {
        policy.setEnabled(ownerProjectId = "owner", enabled = true)
        repeat(DiscoveryLockoutPolicy.MAX_FAILURES) { policy.recordSecurityFailure("blocked", null, nowEpochMillis = 1L) }
        policy.dismissBlockedBanner()

        policy.resetTrust()

        assertNull(policy.blockedMessage())
        assertFalse(policy.isEnabled())
        assertFalse(policy.blockedBannerDismissed())
        assertTrue(store.trust.countedFailureEpochMillis.isEmpty())
        assertEquals("owner", policy.ownerProjectId())
    }

    @Test
    fun `turning discovery off keeps the previous owner`() {
        policy.setEnabled(ownerProjectId = "owner", enabled = true)
        policy.setEnabled(ownerProjectId = "other", enabled = false)

        assertEquals("owner", policy.ownerProjectId())
        assertFalse(policy.isEnabled())
    }

    @Test
    fun `remote access is off until it is turned on`() {
        assertFalse(policy.isRemoteAccessEnabled())

        policy.setRemoteAccess(true)

        assertTrue(policy.isRemoteAccessEnabled())
    }

    @Test
    fun `a blocking security failure turns remote access off`() {
        policy.setEnabled(ownerProjectId = "owner", enabled = true)
        policy.setRemoteAccess(true)

        repeat(DiscoveryLockoutPolicy.MAX_FAILURES - 1) { policy.recordSecurityFailure("failure", null, 1L) }
        assertTrue(policy.isRemoteAccessEnabled())

        assertTrue(policy.recordSecurityFailure("last failure", null, nowEpochMillis = 2L))
        assertFalse(policy.isRemoteAccessEnabled())
    }

    @Test
    fun `reset trust turns remote access off`() {
        policy.setRemoteAccess(true)

        policy.resetTrust()

        assertFalse(policy.isRemoteAccessEnabled())
    }
}
