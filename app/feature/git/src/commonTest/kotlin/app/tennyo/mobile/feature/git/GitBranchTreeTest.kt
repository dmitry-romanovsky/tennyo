package app.tennyo.mobile.feature.git


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitBranchTreeTest {
    @Test
    fun `tree preserves recursive paths stable scope ids and duplicate leaves`() {
        val tree = buildRemoteGitBranchTree(
            listOf(branch("one", "feature/mobile/login"), branch("two", "fix/mobile/login")),
            scope = "local",
        )
        val visible = visibleRemoteGitBranchNodes(tree, emptySet(), revealAll = true)

        assertEquals(listOf("feature", "mobile", "login", "fix", "mobile", "login"), visible.map { it.node.name })
        assertEquals(2, visible.count { it.node is GitBranchNode.Leaf && it.node.name == "login" })
        assertTrue(visible.map { it.node.id }.all { it.startsWith("local:") })
        assertEquals(visible.size, visible.map { it.node.id }.distinct().size)
    }

    @Test
    fun `search exposes matching leaves and all ancestors without changing expansion state`() {
        val branches = listOf(branch("one", "feature/mobile/login"), branch("two", "fix/server"))
        val expansion = setOf("local:folder:fix")
        val filtered = filterRemoteGitBranches(branches, "login")
        val visible = visibleRemoteGitBranchNodes(
            buildRemoteGitBranchTree(filtered, "local"),
            expandedFolders = expansion,
            revealAll = true,
        )

        assertEquals(listOf("feature", "mobile", "login"), visible.map { it.node.name })
        assertEquals(setOf("local:folder:fix"), expansion)
    }

    @Test
    fun `local and remote trees use equivalent hierarchy with separate stable scopes`() {
        val branches = listOf(branch("one", "feature/mobile/login"))
        val local = visibleRemoteGitBranchNodes(buildRemoteGitBranchTree(branches, "local"), emptySet(), true)
        val remote = visibleRemoteGitBranchNodes(buildRemoteGitBranchTree(branches, "remote"), emptySet(), true)

        assertEquals(local.map { it.node.name }, remote.map { it.node.name })
        assertTrue(local.all { it.node.id.startsWith("local:") })
        assertTrue(remote.all { it.node.id.startsWith("remote:") })
    }

    private fun branch(id: String, name: String) = GitBranch(id, name, name)
}
