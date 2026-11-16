package app.tennyo.contract

object SessionTimeouts {
    const val PENDING_APPROVAL_TIMEOUT_MILLIS = 2 * 60 * 1_000L
    const val APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS = 3 * 60 * 1_000L
    const val ABSOLUTE_SESSION_TIMEOUT_MILLIS = 12 * 60 * 60 * 1_000L
    const val REMEMBERED_PAIR_TTL_MILLIS = 14 * 24 * 60 * 60 * 1_000L
}

/** Local-network discovery: hosts advertise this DNS-SD service type and the app browses for it. */
object Discovery {
    const val SERVICE_TYPE = "_tennyo-link._tcp"

    /** TXT key carrying [ProtocolVersions.SECURITY_VERSION], so a phone can skip a host it cannot speak to. */
    const val TXT_SECURITY_VERSION = "v"

    /** TXT key: `1` while a pairing window is open. A hint for probe order; the offer itself is the answer. */
    const val TXT_PAIRING_WINDOW = "p"
}

/**
 * The single protocol/security version shared by pairing, device proofs, remembered-pair proofs and call MACs. It is
 * bound into every crypto transcript as an explicit line, so bumping it re-derives every proof and key at once. Client
 * and host must match exactly; a mismatch is reported as [LinkFaultCode.PROTOCOL_VERSION_UNSUPPORTED].
 */
object ProtocolVersions {
    const val SECURITY_VERSION = 2
}

/** Shape of the pairing word grid the user taps to confirm a new device. */
object PairingWords {
    /** Words the companion displays, in order, and the user taps on the client. */
    const val SECRET_LENGTH = 4

    /** Tiles shown on the client: the secret words plus decoys, sorted so the secret order is not derivable. */
    const val GRID_SIZE = 16
}

object ErrorCodes {
    const val INVALID_REQUEST = "invalid_request"
    const val SECURE_TRANSPORT_REQUIRED = "secure_transport_required"
    const val CONNECTION_REJECTED = "connection_rejected"
    const val CONNECTION_REQUEST_PENDING = "connection_request_pending"
    const val CONNECTION_REQUEST_TIMED_OUT = "connection_request_timed_out"
    const val METHOD_NOT_ALLOWED = "method_not_allowed"
    const val REQUEST_TOO_LARGE = "request_too_large"
    const val NOT_FOUND = "not_found"
    const val RATE_LIMITED = "rate_limited"
    const val PIPELINING_NOT_SUPPORTED = "pipelining_not_supported"
    const val SERVER_BUSY = "server_busy"
    const val INTERNAL_ERROR = "internal_error"
}
