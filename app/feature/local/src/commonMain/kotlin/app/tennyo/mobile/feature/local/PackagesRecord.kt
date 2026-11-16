package app.tennyo.mobile.feature.local

import kotlinx.serialization.Serializable

/**
 * What the user agreed to install for a workspace, kept inside its packages directory so deleting the packages deletes
 * the consent. [requirements] are the lines of `requirements.txt` then, [added] the packages named after an import
 * failed.
 */
@Serializable
data class PackagesRecord(
    val requirements: List<String>,
    val added: List<String>,
)

/** The file in a packages directory, or in a staging directory about to become one, that holds the [PackagesRecord]. */
internal const val PACKAGES_RECORD_FILE = "tennyo-packages.json"
