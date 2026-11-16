package app.tennyo.mobile.link.pairing

import app.tennyo.contract.LinkEndpoints

/** Why a typed host address was refused. The UI turns each case into text; nothing branches on that text. */
enum class RemoteAddressError {
    EMPTY,
    NOT_A_HOST,
    BAD_PORT,
    IPV6_UNSUPPORTED,
    NOT_AN_ADDRESS,

    /** This platform's transport refuses a pinned host name, so only an IP literal can be typed. */
    HOST_NAMES_UNSUPPORTED,
}

sealed interface RemoteAddressParse {
    data class Valid(val endpoint: String) : RemoteAddressParse
    data class Invalid(val error: RemoteAddressError) : RemoteAddressParse
}

/** Whether a typed DNS name may be dialed under a pin on this platform; decided by the iOS manual check. */
expect val allowsTypedHostNames: Boolean

/** A host address the user typed: an IPv4 literal or a DNS name, with an optional `:port` and `https://`. */
object RemoteAddress {
    /** The first listener port both editors try. */
    const val DEFAULT_PORT = 63343

    private const val SCHEME = "https://"
    private const val MAX_ASCII = 127
    private const val MAX_PORT = 65_535
    private const val MAX_HOST_LENGTH = 253
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
    private val FORBIDDEN = setOf('@', '/', '?', '#', '_', '\\')

    @Suppress("ReturnCount")
    fun parse(text: String, defaultPort: Int = DEFAULT_PORT): RemoteAddressParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return invalid(RemoteAddressError.EMPTY)
        val input = if (trimmed.startsWith(SCHEME, ignoreCase = true)) trimmed.substring(SCHEME.length) else trimmed
        charactersError(input)?.let { return invalid(it) }
        val port = if (':' in input) input.substringAfter(':').toPort() else defaultPort
        port ?: return invalid(RemoteAddressError.BAD_PORT)
        val host = input.substringBefore(':')
        hostError(host)?.let { return invalid(it) }
        return RemoteAddressParse.Valid("$SCHEME${host.lowercase()}:$port${LinkEndpoints.PREFIX}")
    }

    /** The port of [endpoint], or null when it names none. */
    fun portOf(endpoint: String): Int? =
        endpoint.substringAfter("://").substringBefore('/').substringAfter(':', "").toIntOrNull()

    private fun charactersError(input: String): RemoteAddressError? = when {
        '[' in input || input.count { it == ':' } > 1 -> RemoteAddressError.IPV6_UNSUPPORTED
        input.any(::isForbidden) -> RemoteAddressError.NOT_AN_ADDRESS
        // Anything after the colon that is not a number reads as a scheme ("ftp:x"), not as a bad port.
        input.substringAfter(':', "").any { !it.isAsciiDigit() } -> RemoteAddressError.NOT_AN_ADDRESS
        else -> null
    }

    private fun hostError(host: String): RemoteAddressError? = when {
        isIpv4(host) -> RemoteAddressError.NOT_AN_ADDRESS.takeIf { host.startsWith("127.") || host == "0.0.0.0" }
        !isDnsName(host) -> RemoteAddressError.NOT_A_HOST
        !allowsTypedHostNames -> RemoteAddressError.HOST_NAMES_UNSUPPORTED
        else -> null
    }

    private fun isForbidden(char: Char) =
        char in FORBIDDEN || char.code > MAX_ASCII || char.isWhitespace() || char.isISOControl()

    private fun String.toPort(): Int? = toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    private fun invalid(error: RemoteAddressError) = RemoteAddressParse.Invalid(error)

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == IPV4_PARTS &&
            parts.all { part -> part.isNotEmpty() && part.all(Char::isAsciiDigit) && part.toInt() <= MAX_OCTET }
    }

    private fun isDnsName(host: String): Boolean {
        val labels = host.split('.')
        return host.length <= MAX_HOST_LENGTH &&
            labels.all { it.isValidLabel() } &&
            !labels.last().all(Char::isAsciiDigit)
    }
}

private const val MAX_LABEL_LENGTH = 63

private fun String.isValidLabel() =
    length in 1..MAX_LABEL_LENGTH && all(Char::isLdh) && !startsWith('-') && !endsWith('-')

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun Char.isLdh() = this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit() || this == '-'
