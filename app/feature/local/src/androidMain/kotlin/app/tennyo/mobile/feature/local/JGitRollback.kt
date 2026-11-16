package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitChange
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * Discards [changes]: paths `HEAD` has go back to it in the index and on disk; paths it lacks — new and untracked
 * files — leave the index and the disk.
 */
internal fun rollBack(repository: Repository, directory: File, changes: List<GitChange>) {
    val head = headTree(repository)
    val (tracked, added) = changes.flatMap { listOfNotNull(it.beforePath, it.afterPath) }.distinct()
        .partition { path -> head != null && TreeWalk.forPath(repository, path, head) != null }
    val git = Git(repository)
    if (tracked.isNotEmpty()) {
        git.reset().apply { tracked.forEach(::addPath) }.call()
        git.checkout().apply { tracked.forEach(::addPath) }.call()
    }
    added.forEach { path ->
        git.rm().setCached(true).addFilepattern(path).call()
        File(directory, path).delete()
    }
}
