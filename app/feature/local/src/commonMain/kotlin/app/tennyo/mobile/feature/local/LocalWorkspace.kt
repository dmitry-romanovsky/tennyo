package app.tennyo.mobile.feature.local

import kotlinx.serialization.Serializable

/**
 * An on-device workspace: a directory named [slug] inside the app's private storage, and the editor tabs it had open
 * ([openedTabs] are paths relative to the workspace root).
 */
@Serializable
data class LocalWorkspace(
    val name: String,
    val slug: String,
    val createdAtEpochMillis: Long,
    val openedTabs: List<String> = emptyList(),
    val selectedTab: Int = 0,
)

/**
 * The directory name for a workspace called [name]: lowercase letters, digits and single dashes, with `-2`, `-3`, …
 * appended while the result is already [taken].
 */
fun workspaceSlug(name: String, taken: Set<String>): String {
    val base = name.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .split('-')
        .filter(String::isNotEmpty)
        .joinToString("-")
        .take(MAX_SLUG_LENGTH)
        .trimEnd('-')
        .ifEmpty { DEFAULT_SLUG }
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
}

private const val MAX_SLUG_LENGTH = 48
private const val DEFAULT_SLUG = "workspace"
