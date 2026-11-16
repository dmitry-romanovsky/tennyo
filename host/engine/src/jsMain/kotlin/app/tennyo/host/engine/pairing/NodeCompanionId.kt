package app.tennyo.host.engine.pairing

import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.asText
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.jsOptions

/**
 * This installation's id, which a phone remembers alongside the pair so it can tell one host from another.
 *
 * It lives with the pair records and outlives them: forgetting it would make every remembered phone treat this host
 * as one it has never met. A host that loses the file is a new companion, which is the right answer after a wipe.
 */
fun nodeCompanionId(directory: String = defaultLinkStateDirectory()): String {
    val path = NodePath.join(directory, "companion-id")
    runCatching { NodeFs.readFileSync(path).asText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val created = hostRandomUuid()
    runCatching {
        NodeFs.mkdirSync(directory, jsOptions("recursive" to true, "mode" to OWNER_ONLY_DIRECTORY))
        NodeFs.writeFileSync(path, created, jsOptions("encoding" to "utf8", "mode" to OWNER_ONLY_FILE))
    }
    return created
}

private const val OWNER_ONLY_DIRECTORY = 448
private const val OWNER_ONLY_FILE = 384
