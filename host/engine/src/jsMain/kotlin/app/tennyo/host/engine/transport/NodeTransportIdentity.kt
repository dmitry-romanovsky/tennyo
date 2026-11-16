package app.tennyo.host.engine.transport

import app.tennyo.host.engine.pairing.defaultLinkStateDirectory
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.asText
import app.tennyo.host.engine.platform.jsOptions
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.rpc.hostJson
import kotlinx.serialization.Serializable

/**
 * The listener's TLS material and the pin the phone checks.
 *
 * [fingerprint] is base64 of the SHA-256 of the certificate's SPKI, computed exactly as the IntelliJ listener does:
 * a phone paired against one host must be able to verify the other without knowing which one it reached.
 */
@Serializable
class NodeTransportIdentity(
    val fingerprint: String,
    val certificatePem: String,
    val privateKeyPem: String,
)

private const val CERTIFICATE_VALIDITY_DAYS = 3650

fun newNodeTransportIdentity(): NodeTransportIdentity {
    val commonName = "tennyo-mobile-${NodeCrypto.randomUUID()}.local"
    val pair = SelfSigned.generate(
        arrayOf(jsOptions("name" to "commonName", "value" to commonName)),
        jsOptions("keySize" to 2048, "days" to CERTIFICATE_VALIDITY_DAYS, "algorithm" to "sha256"),
    )
    val certificatePem = pair.cert as String
    return NodeTransportIdentity(
        fingerprint = certificatePem.spkiSha256Base64(),
        certificatePem = certificatePem,
        privateKeyPem = pair.private as String,
    )
}

private fun String.spkiSha256Base64(): String {
    val spki = NodeCrypto.createPublicKey(this).export(jsOptions("type" to "spki", "format" to "der"))
    return NodeCrypto.createHash("sha256").update(spki).digest("base64")
}

/**
 * The host's certificate, kept across restarts.
 *
 * It must be: a remembered phone pins the fingerprint it paired against, so a listener that generated a fresh
 * certificate every start would make every remembered pair fail its next reconnect. The file sits with the pair
 * records, owner-readable only, since the private key is what proves this host is itself.
 */
fun loadOrCreateNodeTransportIdentity(
    directory: String = defaultLinkStateDirectory(),
    fileName: String = "transport-identity.json",
): NodeTransportIdentity {
    val path = NodePath.join(directory, fileName)
    storedIdentity(path)?.let { return it }
    val created = newNodeTransportIdentity()
    runCatching {
        NodeFs.mkdirSync(directory, jsOptions("recursive" to true, "mode" to OWNER_ONLY_DIRECTORY))
        NodeFs.writeFileSync(path, hostJson(created), jsOptions("encoding" to "utf8", "mode" to OWNER_ONLY_FILE))
    }.onFailure {
        LOG.warn("Unable to store the transport identity; remembered devices will have to pair again on restart.")
    }
    return created
}

private fun storedIdentity(path: String): NodeTransportIdentity? {
    val text = runCatching { NodeFs.readFileSync(path).asText() }.getOrNull() ?: return null
    return runCatching { HostJson.decodeFromString<NodeTransportIdentity>(text) }
        .onFailure { LOG.warn("Unable to read the stored transport identity; a new one will be generated.") }
        .getOrNull()
        ?.takeIf { it.certificatePem.isNotBlank() && it.privateKeyPem.isNotBlank() && it.fingerprint.isNotBlank() }
}

/** `0700` and `0600`, written in octal the way the mode is documented. */
private const val OWNER_ONLY_DIRECTORY = 448
private const val OWNER_ONLY_FILE = 384
private val LOG = HostLogger("NodeTransportIdentity")
