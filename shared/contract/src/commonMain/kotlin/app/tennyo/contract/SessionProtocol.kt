package app.tennyo.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Session/project/panel capability identifiers advertised by the companion and gated on by clients. */
@Serializable
enum class Capability {
    FILES,
    LAUNCH_CONFIGURATIONS,
    TERMINAL,
    GIT,

    /** The agent conversation: a host reports it when the user's configuration names at least one ACP agent. */
    CHAT,
}

/**
 * A single pairing attempt's challenge, plus the material the client needs to answer it.
 *
 * [challengeId] and [serverChallenge] rotate on every failed attempt. [wordGrid], [gridSeed] and
 * [companionEphemeralPublicKey] are generated once per session and repeated on every rotated challenge: the secret
 * words never change, so re-rolling them would buy no security and force the user to re-scan the grid.
 *
 * Every field is required. A challenge without a grid would render a pairing step with no tiles and no way forward,
 * so failing at deserialization is the better outcome.
 */
@Serializable
data class PairingChallenge(
    val challengeId: String,
    val serverChallenge: String,
    val expiresAtEpochMillis: Long,
    /**
     * [PairingWords.GRID_SIZE] distinct words, alphabetically sorted, containing the [PairingWords.SECRET_LENGTH]
     * secret words the companion displays. A `List` rather than a `Set`: an insertion-ordered set built as
     * `secret + decoys` would put the secret words first on the wire.
     */
    val wordGrid: List<String>,
    /**
     * Base64url seed deriving each tile's key as `HMAC(gridSeed, "tennyo-pairing-tile" + word)`. Keyed by word rather
     * than position so the derivation survives re-sorting on either side. The keys are not secret — the client, and
     * therefore an attacker who reaches this point, can derive all of them. The secret is which words, in which order.
     */
    val gridSeed: String,
    /** Base64url SPKI EC P-256 public key for the ephemeral exchange that carries the pair material. */
    val companionEphemeralPublicKey: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

@Serializable
data class SessionClaimRequest(
    val sessionId: String,
    val clientName: String,
    val clientVersion: String,
    val clientId: String = "android",
    val devicePublicKey: String = "",
    val deviceFingerprint: String = "",
    val rememberedPairId: String? = null,
)

/** The editor family a host runs in, so the phone can point at that editor's own settings. */
@Serializable
enum class HostEditor {
    @SerialName("jetbrains")
    JETBRAINS,

    @SerialName("vscode")
    VS_CODE,

    @SerialName("cursor")
    CURSOR,

    @SerialName("devin_desktop")
    DEVIN_DESKTOP,
}

/**
 * [HostEditor] as an optional field: a value this build does not know — a newer host's editor — reads as absent, so it
 * never fails the reply that carries it. Scoped to this field so every other enum on the link stays strict.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object OptionalHostEditorSerializer : KSerializer<HostEditor?> {
    private val names = HostEditor.entries.associateBy { HostEditor.serializer().descriptor.getElementName(it.ordinal) }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.tennyo.contract.OptionalHostEditor", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: HostEditor?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(HostEditor.serializer().descriptor.getElementName(value.ordinal))
        }
    }

    override fun deserialize(decoder: Decoder): HostEditor? =
        if (decoder.decodeNotNullMark()) names[decoder.decodeString()] else decoder.decodeNull()
}

@Serializable
data class SessionResponse(
    val sessionId: String,
    val projectName: String,
    val projectPath: String,
    val projectChoices: List<ProjectChoice> = emptyList(),
    val companionId: String? = null,
    val hostVersion: String? = null,
    /** Absent from a host that predates it, and read as absent when this app does not know the value. */
    @Serializable(with = OptionalHostEditorSerializer::class)
    val editor: HostEditor? = null,
    val capabilities: Set<Capability>,
    val stats: SessionStats,
    val requiresPairingConfirmation: Boolean = false,
    val pairingChallenge: PairingChallenge? = null,
    val approvedDeviceIdleTimeoutMillis: Long = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS,
    val serverTimeEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class SessionConfirmPairingRequest(
    val sessionId: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val challengeId: String,
    val attemptId: String,
    /**
     * Base64url SPKI EC P-256 public key for this attempt. Both ephemeral keys are covered by
     * [clientPairingProof]'s transcript — without that binding a relay could substitute its own key and the
     * exchange would authenticate nothing.
     */
    val clientEphemeralPublicKey: String,
    /** HMAC over the pairing transcript, keyed by the concatenated tile keys of the tapped words in tap order. */
    val clientPairingProof: String,
)

@Serializable
data class PairingConfirmationResponse(
    val status: PairingConfirmationStatus,
    val session: SessionResponse? = null,
    val pairingChallenge: PairingChallenge? = null,
    val encryptedPairMaterial: String? = null,
    /**
     * The approved device's [SessionTicket] sealed under the pairing ECDH key, so the session token never leaves
     * the host in the clear. Set on [PairingConfirmationStatus.Success] for a device that paired by name.
     */
    val encryptedArtifact: String? = null,
)

@Serializable
enum class PairingConfirmationStatus {
    @SerialName("success")
    Success,

    @SerialName("incorrect_selection")
    IncorrectSelection,

    @SerialName("stopped_server_exceeded_maximum_attempts")
    StoppedServerExceededMaximumAttempts,
}

@Serializable
data class RememberedPairResumeRequest(
    val sessionId: String,
    val pairId: String,
    val challengeId: String,
    val attemptId: String,
    /**
     * Base64url SPKI EC P-256 public key for this attempt. Covered by [clientPairProof]'s transcript, and paired
     * with the challenge's companion key so the rotated pair material travels under a key the current pairSecret
     * cannot reconstruct.
     */
    val clientEphemeralPublicKey: String,
    val clientPairProof: String,
)

@Serializable
data class RememberedPairResumeResponse(
    val status: RememberedPairResumeStatus,
    val session: SessionResponse? = null,
    val encryptedRotatedPair: String? = null,
    val pairingChallenge: PairingChallenge? = null,
)

@Serializable
enum class RememberedPairResumeStatus {
    @SerialName("success")
    Success,

    @SerialName("rejected")
    Rejected,
}

@Serializable
data class HandshakeResponse(
    val sessionId: String,
    val projectName: String,
    val projectPath: String,
    val projectChoices: List<ProjectChoice> = emptyList(),
    val companionId: String? = null,
    val hostVersion: String? = null,
    @Serializable(with = OptionalHostEditorSerializer::class)
    val editor: HostEditor? = null,
    val capabilities: Set<Capability>,
    val securityVersion: Int = ProtocolVersions.SECURITY_VERSION,
    val approvedDeviceIdleTimeoutMillis: Long = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS,
    val serverTimeEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
/** Asks a host on the local network whether it knows [pairId]. */
data class DiscoveryLookup(
    val companionId: String,
    val pairId: String,
)

@Serializable
data class DiscoverResponse(
    val status: DiscoveryStatus,
    val challenge: DiscoveryChallenge? = null,
    val descriptor: KnownHostDescriptor? = null,
    val projectChoices: List<ProjectChoice> = emptyList(),
    val blockedMessage: String? = null,
)

@Serializable
data class DiscoveryConnectRequest(
    val companionId: String,
    val pairId: String,
    val challengeId: String,
    val attemptId: String,
    /** Base64url SPKI EC P-256 public key for this attempt; covered by [clientPairProof]'s transcript. */
    val clientEphemeralPublicKey: String,
    val clientPairProof: String,
    /** Required, not defaulted: kotlinx omits defaults, and the companion rejects a missing version as zero. */
    val securityVersion: Int,
    val projectId: String? = null,
)

@Serializable
data class DiscoveryConnectResponse(
    val status: DiscoveryStatus,
    val encryptedArtifact: String? = null,
    val encryptedRotatedPair: String? = null,
    val blockedMessage: String? = null,
)

@Serializable
enum class DiscoveryStatus {
    @SerialName("available")
    AVAILABLE,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("pair_revoked")
    PAIR_REVOKED,
}

@Serializable
data class KnownHostDescriptor(
    val companionId: String,
    val companionLabel: String,
    val projectName: String,
    val hostVersion: String,
    val lastUsedAtEpochMillis: Long,
    val lastKnownNetworkLabel: String? = null,
)

@Serializable
data class ProjectChoice(
    val projectId: String,
    val projectName: String,
    val projectPath: String? = null,
)

@Serializable
data class DiscoveryChallenge(
    val challengeId: String,
    val serverChallenge: String,
    val connectToken: String,
    /** Base64url SPKI EC P-256 public key, fresh per challenge, carrying the connect payload. */
    val companionEphemeralPublicKey: String,
    val expiresAtEpochMillis: Long,
)

@OptIn(ExperimentalTime::class)
@Serializable
data class SessionStats(
    val transport: String = "Direct",
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val totalUploadedBytes: Long = 0,
    val totalDownloadedBytes: Long = 0,
    val reconnectCount: Int = 0,
    val sessionStartedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val hostName: String = "IDE Host",
    val lastPingMillis: Long? = null,
) {
    companion object
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ActiveSurface(
    val panel: String,
    val selectedFilePath: String? = null,
    val selectedLaunchTabId: String? = null,
    val selectedTerminalId: String? = null,
)

object ActivePanels {
    const val FILES = "FILES"
    const val LAUNCH = "LAUNCH"
    const val TERMINAL = "TERMINAL"
    const val GIT = "GIT"
}

fun ActiveSurface?.allowsMetadataPanel(panel: String): Boolean =
    this?.panel == panel

fun ActiveSurface?.selectedVisibleTerminalId(): String? =
    when (this?.panel) {
        ActivePanels.TERMINAL -> selectedTerminalId
        else -> null
    }

@Serializable
enum class SessionEndReason {
    @SerialName("pending_expired")
    PendingExpired,

    @SerialName("idle_expired")
    IdleExpired,

    @SerialName("absolute_expired")
    AbsoluteExpired,
}
