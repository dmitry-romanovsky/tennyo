package app.tennyo.host.engine.transport

import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.platform.hostWallClockMillis

/** Bounded transport admission shared by every route before authentication or IDE work. */
class AdmissionPolicy {
    private val lock = HostMonitor()
    private var pendingTlsHandshakes = 0
    private val pendingTlsBySource = mutableMapOf<String, Int>()
    private var provisionalTlsChannels = 0
    private val provisionalTlsBySource = mutableMapOf<String, Int>()
    private var nonSocketChannels = 0
    private val nonSocketChannelsBySource = mutableMapOf<String, Int>()
    private val ordinaryChannelsBySource = mutableMapOf<String, Int>()
    private val linkSocketsBySource = mutableMapOf<String, Int>()
    private val requestsBySource = mutableMapOf<String, SlidingWindow>()
    private val publicRequests = SlidingWindow()
    private val livenessRequestsBySource = mutableMapOf<String, SlidingWindow>()
    private val allLivenessRequests = SlidingWindow()

    fun beginTlsHandshake(source: String): TlsHandshakePermit? = lock.guarded {
        val handshakesLeft = pendingTlsHandshakes < MAX_PENDING_TLS_HANDSHAKES &&
                pendingTlsBySource.orZero(source) < MAX_PENDING_TLS_HANDSHAKES_PER_SOURCE
        if (!handshakesLeft || !hasChannelCapacity(source)) {
            null
        } else {
            pendingTlsHandshakes++
            pendingTlsBySource.increment(source)
            nonSocketChannels++
            nonSocketChannelsBySource.increment(source)
            TlsHandshakePermit(source)
        }
    }

    fun finishTlsHandshake(permit: TlsHandshakePermit) = permit.finish()

    fun abortTlsHandshake(permit: TlsHandshakePermit) = permit.close()

    fun openOrdinaryHttp(source: String): ChannelPermit? = lock.guarded {
        if (!canOpenOrdinary(source)) null else {
            nonSocketChannels++
            nonSocketChannelsBySource.increment(source)
            ordinaryChannelsBySource.increment(source)
            ChannelPermit(source, AdmissionClass.ORDINARY_HTTP)
        }
    }

    fun openLinkSocket(source: String): ChannelPermit? = lock.guarded {
        if (!canOpenLinkSocket(source)) null else {
            linkSocketsBySource.increment(source)
            ChannelPermit(source, AdmissionClass.LINK_SOCKET)
        }
    }

    fun promoteToOrdinaryHttp(permit: TlsHandshakePermit): ChannelPermit? = permit.promoteOrdinary()

    fun promoteToLinkSocket(permit: TlsHandshakePermit): ChannelPermit? = permit.promoteLinkSocket()

    fun snapshot(): AdmissionSnapshot = lock.guarded {
        AdmissionSnapshot(
            pendingTlsHandshakes = pendingTlsHandshakes,
            provisionalTlsChannels = provisionalTlsChannels,
            openChannels = nonSocketChannels + linkSocketsBySource.values.sum(),
            ordinaryChannels = ordinaryChannelsBySource.values.sum(),
            linkSockets = linkSocketsBySource.values.sum(),
        )
    }

    fun admitRequest(
        source: String,
        isLivenessProbe: Boolean,
        nowMillis: Long = hostWallClockMillis(),
    ): Boolean = lock.guarded {
        if (isLivenessProbe) {
            return@guarded allLivenessRequests.tryAcquire(nowMillis, MAX_LIVENESS_REQUESTS_GLOBAL_PER_MINUTE) &&
                    livenessRequestsBySource.getOrPut(source) { SlidingWindow() }
                        .tryAcquire(nowMillis, MAX_LIVENESS_REQUESTS_PER_MINUTE)
        }
        publicRequests.tryAcquire(nowMillis, MAX_PUBLIC_REQUESTS_GLOBAL_PER_MINUTE) &&
                requestsBySource.getOrPut(source) { SlidingWindow() }
                    .tryAcquire(nowMillis, MAX_PUBLIC_REQUESTS_PER_MINUTE)
    }

    /** Whether one more connection from [source] fits, counting link sockets and everything else together. */
    private fun hasChannelCapacity(source: String): Boolean =
        nonSocketChannels + linkSocketsBySource.values.sum() < MAX_CHANNELS &&
                nonSocketChannelsBySource.orZero(source) + linkSocketsBySource.orZero(source) < MAX_CHANNELS_PER_SOURCE

    private fun canOpenOrdinary(source: String): Boolean =
        ordinaryChannelsBySource.values.sum() < MAX_ORDINARY_HTTP_CHANNELS &&
                ordinaryChannelsBySource.orZero(source) < MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE &&
                nonSocketChannels + linkSocketsBySource.values.sum() < MAX_CHANNELS &&
                nonSocketChannelsBySource.orZero(source) + linkSocketsBySource.orZero(source) < MAX_CHANNELS_PER_SOURCE

    private fun canOpenLinkSocket(source: String): Boolean =
        linkSocketsBySource.values.sum() < MAX_LINK_SOCKETS &&
                linkSocketsBySource.orZero(source) < MAX_LINK_SOCKETS_PER_SOURCE &&
                nonSocketChannels + linkSocketsBySource.values.sum() < MAX_CHANNELS &&
                nonSocketChannelsBySource.orZero(source) + linkSocketsBySource.orZero(source) < MAX_CHANNELS_PER_SOURCE

    private fun finishTls(source: String) = lock.guarded {
        pendingTlsHandshakes--
        pendingTlsBySource.decrement(source)
        provisionalTlsChannels++
        provisionalTlsBySource.increment(source)
    }

    private fun closePendingTls(source: String) = lock.guarded {
        pendingTlsHandshakes--
        pendingTlsBySource.decrement(source)
        releaseNonSocket(source)
    }

    private fun closeProvisionalTls(source: String) = lock.guarded {
        provisionalTlsChannels--
        provisionalTlsBySource.decrement(source)
        releaseNonSocket(source)
    }

    private fun promoteOrdinary(source: String): ChannelPermit? = lock.guarded {
        if (ordinaryChannelsBySource.values.sum() >= MAX_ORDINARY_HTTP_CHANNELS ||
            ordinaryChannelsBySource.orZero(source) >= MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE
        ) return@guarded null
        provisionalTlsChannels--
        provisionalTlsBySource.decrement(source)
        ordinaryChannelsBySource.increment(source)
        ChannelPermit(source, AdmissionClass.ORDINARY_HTTP)
    }

    private fun promoteLinkSocket(source: String): ChannelPermit? = lock.guarded {
        if (!canOpenLinkSocket(source)) return@guarded null
        provisionalTlsChannels--
        provisionalTlsBySource.decrement(source)
        releaseNonSocket(source)
        linkSocketsBySource.increment(source)
        ChannelPermit(source, AdmissionClass.LINK_SOCKET)
    }

    private fun closeChannel(source: String, admissionClass: AdmissionClass) = lock.guarded {
        when (admissionClass) {
            AdmissionClass.ORDINARY_HTTP -> {
                ordinaryChannelsBySource.decrement(source)
                releaseNonSocket(source)
            }

            AdmissionClass.LINK_SOCKET -> linkSocketsBySource.decrement(source)
        }
    }

    private fun releaseNonSocket(source: String) {
        nonSocketChannels--
        nonSocketChannelsBySource.decrement(source)
    }

    inner class TlsHandshakePermit internal constructor(private val source: String) {
        private var state = TlsPermitState.PENDING

        internal fun finish() = lock.guarded {
            if (state == TlsPermitState.PENDING) {
                finishTls(source)
                state = TlsPermitState.PROVISIONAL
            }
        }

        internal fun promoteOrdinary(): ChannelPermit? = lock.guarded {
            if (state != TlsPermitState.PROVISIONAL) return@guarded null
            promoteOrdinary(source)?.also { state = TlsPermitState.ORDINARY }
        }

        internal fun promoteLinkSocket(): ChannelPermit? = lock.guarded {
            if (state != TlsPermitState.PROVISIONAL) return@guarded null
            promoteLinkSocket(source)?.also { state = TlsPermitState.LINK_SOCKET }
        }

        internal fun close() = lock.guarded {
            when (state) {
                TlsPermitState.PENDING -> closePendingTls(source)
                TlsPermitState.PROVISIONAL -> closeProvisionalTls(source)
                TlsPermitState.ORDINARY, TlsPermitState.LINK_SOCKET, TlsPermitState.CLOSED -> Unit
            }
            state = TlsPermitState.CLOSED
        }
    }

    inner class ChannelPermit internal constructor(
        private val source: String,
        private val admissionClass: AdmissionClass,
    ) {
        private var closed = false

        fun close() = lock.guarded {
            if (!closed) {
                closed = true
                closeChannel(source, admissionClass)
            }
        }
    }

    enum class AdmissionClass { ORDINARY_HTTP, LINK_SOCKET }

    private enum class TlsPermitState { PENDING, PROVISIONAL, ORDINARY, LINK_SOCKET, CLOSED }

    data class AdmissionSnapshot(
        val pendingTlsHandshakes: Int,
        val provisionalTlsChannels: Int,
        val openChannels: Int,
        val ordinaryChannels: Int,
        val linkSockets: Int,
    )

    /** Only ever touched under the policy's monitor. */
    private class SlidingWindow {
        private val timestamps = ArrayDeque<Long>()

        fun tryAcquire(now: Long, maximum: Int): Boolean {
            while (timestamps.firstOrNull()?.let { now - it >= MINUTE } == true) timestamps.removeFirst()
            if (timestamps.size >= maximum) return false
            timestamps.addLast(now)
            return true
        }
    }

    private fun Map<String, Int>.orZero(key: String): Int = this[key] ?: 0

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = orZero(key) + 1
    }

    private fun MutableMap<String, Int>.decrement(key: String) {
        val updated = orZero(key) - 1
        if (updated > 0) this[key] = updated else remove(key)
    }

    companion object {
        const val MAX_CHANNELS = 128
        const val MAX_CHANNELS_PER_SOURCE = 16
        const val MAX_LINK_SOCKETS_PER_SOURCE = 2
        const val MAX_LINK_SOCKETS = 16
        const val MAX_ORDINARY_HTTP_CHANNELS = MAX_CHANNELS - MAX_LINK_SOCKETS
        const val MAX_ORDINARY_HTTP_CHANNELS_PER_SOURCE =
            MAX_CHANNELS_PER_SOURCE - MAX_LINK_SOCKETS_PER_SOURCE
        const val MAX_PENDING_TLS_HANDSHAKES = 32
        const val MAX_PENDING_TLS_HANDSHAKES_PER_SOURCE = 4
        const val MAX_PUBLIC_REQUESTS_PER_MINUTE = 120
        const val MAX_PUBLIC_REQUESTS_GLOBAL_PER_MINUTE = 480
        const val MAX_LIVENESS_REQUESTS_PER_MINUTE = 40
        const val MAX_LIVENESS_REQUESTS_GLOBAL_PER_MINUTE = 480
        private const val MINUTE = 60_000L
    }
}
