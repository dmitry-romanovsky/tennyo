package app.tennyo.mobile.feature.git



internal sealed interface GitBranchNode {
    val id: String
    val name: String

    data class Folder(
        override val id: String,
        override val name: String,
        val children: List<GitBranchNode>,
    ) : GitBranchNode

    data class Leaf(
        override val id: String,
        override val name: String,
        val branch: GitBranch,
    ) : GitBranchNode
}

internal data class GitVisibleBranchNode(
    val node: GitBranchNode,
    val depth: Int,
)

internal fun buildRemoteGitBranchTree(
    branches: List<GitBranch>,
    scope: String,
): List<GitBranchNode> = buildNodes(branches, scope, emptyList())

internal fun filterRemoteGitBranches(branches: List<GitBranch>, query: String): List<GitBranch> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) branches else branches.filter { branch ->
        branch.displayName.contains(normalizedQuery, ignoreCase = true) ||
                branch.fullName.contains(normalizedQuery, ignoreCase = true)
    }
}

private fun buildNodes(
    branches: List<GitBranch>,
    scope: String,
    prefix: List<String>,
): List<GitBranchNode> {
    val depth = prefix.size
    val leaves = branches.filter { it.displayName.split('/').size == depth + 1 }
        .map { GitBranchNode.Leaf("$scope:leaf:${it.id}", it.displayName.substringAfterLast('/'), it) }
    val folders = branches.filter { it.displayName.split('/').size > depth + 1 }
        .groupBy { it.displayName.split('/')[depth] }
        .map { (name, children) ->
            val path = prefix + name
            GitBranchNode.Folder(
                "$scope:folder:${path.joinToString("/")}",
                name,
                buildNodes(children, scope, path)
            )
        }
    return (folders + leaves).sortedWith(compareBy({ it is GitBranchNode.Leaf }, GitBranchNode::name))
}

internal fun visibleRemoteGitBranchNodes(
    nodes: List<GitBranchNode>,
    expandedFolders: Set<String>,
    revealAll: Boolean,
    depth: Int = 0,
): List<GitVisibleBranchNode> = buildList {
    nodes.forEach { node ->
        add(GitVisibleBranchNode(node, depth))
        if (node is GitBranchNode.Folder && (revealAll || node.id in expandedFolders)) {
            addAll(visibleRemoteGitBranchNodes(node.children, expandedFolders, revealAll, depth + 1))
        }
    }
}
