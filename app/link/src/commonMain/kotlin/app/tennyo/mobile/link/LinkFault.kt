package app.tennyo.mobile.link

import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.wireName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Why a link call failed. Everything the link layer throws is one of these, so callers branch on the type and never
 * on text; the message is for logs and, for [Host] faults, the host's own explanation.
 */
sealed class LinkFault(message: String?, cause: Throwable? = null) : Exception(message, cause) {

    /** The host could not be reached, or the connection broke: timeouts, TLS, sockets, a stream that closed. */
    class Transport(cause: Throwable) : LinkFault(cause.message, cause) {
        /** The TCP connect itself timed out, as opposed to a failure after the host answered. */
        val isConnectTimeout: Boolean get() = causes().any(Throwable::isConnectTimeout)
    }

    /**
     * The host answered with an error: over HTTP with [status], or over the channel with status 0. [code] is a
     * [LinkFaultCode] or [app.tennyo.contract.ErrorCodes] wire name, or `http_<status>` when the host sent none.
     */
    sealed class Host(val status: Int, val code: String, message: String) : LinkFault(message) {
        /** [code] as a typed channel fault, when it is one. */
        val linkFault: LinkFaultCode? get() = LinkFaultCode.entries.firstOrNull { it.wireName == code }
    }

    /** The host does not accept this device or its signature. */
    class Auth(status: Int, code: String, message: String) : Host(status, code, message)

    /** The host is shedding load; the same request may be retried after a pause. */
    class RateLimited(status: Int, code: String, message: String) : Host(status, code, message)

    /** The session no longer exists on the host. Nothing short of a new ticket will help. */
    class SessionEnded(status: Int, code: String, message: String) : Host(status, code, message)

    /**
     * What the call pointed at is gone: a closed tab or terminal, a deleted file, or a path the host refused to resolve
     * inside the project.
     */
    class TargetMissing(status: Int, code: String, message: String) : Host(status, code, message)

    /** Any other refusal. */
    class Refused(status: Int, code: String, message: String) : Host(status, code, message)

    /** The host speaks a security protocol this build cannot verify. One side has to be updated. */
    class ProtocolMismatch(val remoteSecurityVersion: Int) :
        LinkFault("The computer speaks security protocol v$remoteSecurityVersion, which this app cannot verify")

    /** The channel closed; [beforeLiveSync] when it did so before the computer accepted it. */
    class StreamClosed(val beforeLiveSync: Boolean) : LinkFault(
        if (beforeLiveSync) "The channel closed before the computer accepted it" else "The channel closed",
    )

    /** The host sent a pairing word grid the pairing screen cannot complete. */
    class PairingGridUnusable : LinkFault("The computer sent an unusable pairing word grid")

    /** The ticket names no address the phone can use. */
    class Unreachable(val reason: UnreachableReason) : LinkFault(reason.name)

    enum class UnreachableReason {
        /** Every address in the ticket is a loopback address. */
        LocalOnlyAddresses,

        /** No address in the ticket answered. */
        NoEndpoints,

        /** The ticket's address could not be pinned to the host's certificate, so it is never dialed. */
        UnpinnedAddress,
    }

    companion object {
        /** The host's error response for [status], classified by [code]. */
        fun fromHost(status: Int, code: String, message: String): Host = when {
            status == HTTP_TOO_MANY_REQUESTS || code == "http_$HTTP_TOO_MANY_REQUESTS" ->
                RateLimited(status, code, message)

            code in SESSION_ENDED_CODES -> SessionEnded(status, code, message)
            code in AUTH_CODES || status == HTTP_UNAUTHORIZED -> Auth(status, code, message)
            code == LinkFaultCode.TARGET_MISSING.wireName -> TargetMissing(status, code, message)
            else -> Refused(status, code, message)
        }

        /** A [LinkFrame.Fault][app.tennyo.contract.LinkFrame.Fault] from the channel, classified like an HTTP error. */
        fun fromLink(fault: LinkFaultCode, message: String): Host =
            if (fault == LinkFaultCode.BUSY) {
                RateLimited(0, fault.wireName, message)
            } else {
                fromHost(status = 0, code = fault.wireName, message = message)
            }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val SESSION_ENDED_CODES =
            setOf(LinkFaultCode.SESSION_EXPIRED, LinkFaultCode.SESSION_NOT_FOUND).map { it.wireName }.toSet()
        private val AUTH_CODES = setOf(
            LinkFaultCode.UNAUTHORIZED,
            LinkFaultCode.WRONG_DEVICE,
            LinkFaultCode.REPLAYED,
            LinkFaultCode.MAC_INVALID,
        ).map { it.wireName }.toSet()
    }
}

/**
 * This failure as a [LinkFault]: faults pass through, anything else is a [LinkFault.Transport]. Cancellation is not a
 * failure and is rethrown; a timeout is one, and becomes a transport fault.
 */
fun Throwable.toLinkFault(): LinkFault = when (this) {
    is TimeoutCancellationException -> LinkFault.Transport(this)
    is CancellationException -> throw this
    is LinkFault -> this
    else -> causes().firstNotNullOfOrNull { it as? LinkFault } ?: LinkFault.Transport(this)
}

/**
 * [runCatching] for suspending work: a failure, a timeout included, becomes the [Result], while cancellation is
 * rethrown so a cancelled caller stops instead of handling its own cancellation as an error.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { failure ->
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
    }

/** The host no longer approves this device for the session, so the pairing has to be redone. */
val Throwable.isDeviceNotApproved: Boolean
    get() = this is LinkFault.Auth && linkFault == LinkFaultCode.WRONG_DEVICE

/** The session is gone on the host (expired or unknown) or speaks a protocol this build cannot verify. */
val Throwable.endsSession: Boolean
    get() = this is LinkFault.SessionEnded || this is LinkFault.ProtocolMismatch

/** A rate-limit refusal anywhere in the cause chain. */
val Throwable.isRateLimited: Boolean
    get() = causes().any { it is LinkFault.RateLimited }

internal fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

private fun Throwable.isConnectTimeout(): Boolean {
    val name = this::class.simpleName
    return name == "TimeoutCancellationException" ||
        name == "ConnectTimeoutException" ||
        (name == "SocketTimeoutException" && message?.contains("failed to connect") == true)
}
