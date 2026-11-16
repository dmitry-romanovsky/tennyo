package app.tennyo.mobile.feature.git

/**
 * The project-relative path that opens [filePath], a repository-relative path, in the editor: the repository's
 * [repositoryPath] below the project joined with it. `null` when the repository lies outside the project or either
 * path is absolute or climbs out of its folder, so a path from the host can never open anything else.
 */
internal fun editorPath(repositoryPath: String?, filePath: String): String? {
    val repository = repositoryPath?.let(::normalizedSegments) ?: return null
    val file = normalizedSegments(filePath) ?: return null
    return (repository + file).takeIf { file.isNotEmpty() }?.joinToString("/")
}

private fun normalizedSegments(path: String): List<String>? {
    if (path.startsWith("/") || '\\' in path || path.getOrNull(1) == ':') return null
    val segments = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments
}
