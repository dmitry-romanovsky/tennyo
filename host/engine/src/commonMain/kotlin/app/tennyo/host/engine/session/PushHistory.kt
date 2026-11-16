package app.tennyo.host.engine.session

import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded

/** One push as it went on the wire: its sequence number and its encoded [app.tennyo.contract.LinkFrame.Push]. */
class SentPush(val seq: Long, val frameText: String)

/**
 * The latest pushes of one session, bounded by count and size, so a client that reconnects gets exactly what it
 * missed. When the pushes after its resume point are no longer all here, it reloads instead.
 */
class PushHistory(
    private val maxPushes: Int,
    private val maxChars: Long,
) {
    private val monitor = HostMonitor()
    private val pushes = ArrayDeque<SentPush>()
    private var retainedChars = 0L
    private var latestDiscardedSeq: Long? = null

    init {
        require(maxPushes > 0)
        require(maxChars > 0)
    }

    fun append(push: SentPush) = monitor.guarded {
        pushes.addLast(push)
        retainedChars += push.frameText.length
        while (pushes.size > maxPushes || retainedChars > maxChars) {
            val discarded = pushes.removeFirst()
            retainedChars -= discarded.frameText.length
            latestDiscardedSeq = discarded.seq
        }
    }

    /**
     * What a client resuming after [lastPushSeq] missed. A client with no resume point starts from a snapshot, and so
     * does one whose next push was already discarded.
     */
    fun resumeAfter(lastPushSeq: Long?): PushResume = monitor.guarded {
        if (lastPushSeq == null) return@guarded PushResume(snapshotRequired = true)
        if (latestDiscardedSeq?.let { lastPushSeq < it } == true) return@guarded PushResume(snapshotRequired = true)
        PushResume(snapshotRequired = false, missed = pushes.filter { it.seq > lastPushSeq })
    }
}

data class PushResume(
    val snapshotRequired: Boolean,
    val missed: List<SentPush> = emptyList(),
)
