package app.tennyo.host.engine.session

import app.tennyo.contract.TerminalTab
import app.tennyo.host.engine.rpc.hostJson
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.atomics.AtomicReference

enum class ConnectionApproval {
    ACCEPTED,
    REJECTED,
    BUSY,
    TIMED_OUT,
}

enum class ConnectionRequestStatus {
    PENDING,
    ACCEPTED,
    AWAITING_PAIRING,
    REJECTED,
}

data class ConnectionRequest(
    val id: String,
    val clientName: String,
    val clientVersion: String,
    val requestedAtEpochMillis: Long,
    val deviceFingerprint: String,
    val pairingWords: List<String>,
    val status: ConnectionRequestStatus,
)

fun launchTabTitle(
    displayName: String?,
    runProfileName: String?,
    configurationName: String?,
): String =
    displayName ?: runProfileName ?: configurationName ?: "Launch"

/** A connection request while the user decides; [decided] completes once the request is accepted or rejected. */
class MutableConnectionRequest(
    val id: String,
    val clientName: String,
    val clientVersion: String,
    val devicePublicKey: String,
    val deviceKeyHash: String,
    val pairingWords: List<String>,
    val canResumeRememberedPair: Boolean,
    val requestedAtEpochMillis: Long,
    status: ConnectionRequestStatus = ConnectionRequestStatus.PENDING,
) {
    val decided = CompletableDeferred<Unit>()

    private val statusValue = AtomicReference(status)

    var status: ConnectionRequestStatus
        get() = statusValue.load()
        set(value) = statusValue.store(value)

    fun toPublicRequest(): ConnectionRequest =
        ConnectionRequest(
            id = id,
            clientName = clientName,
            clientVersion = clientVersion,
            requestedAtEpochMillis = requestedAtEpochMillis,
            deviceFingerprint = deviceKeyHash.take(DEVICE_FINGERPRINT_DISPLAY_LENGTH),
            pairingWords = pairingWords,
            status = status,
        )

    private companion object {
        const val DEVICE_FINGERPRINT_DISPLAY_LENGTH = 16
    }
}
