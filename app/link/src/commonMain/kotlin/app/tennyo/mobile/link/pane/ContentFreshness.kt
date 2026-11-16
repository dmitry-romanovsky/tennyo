package app.tennyo.mobile.link.pane

import kotlin.time.Clock

/** Content older than this is refetched the next time its pane is shown or synced. */
const val CONTENT_STALE_MILLIS = 30_000L

/** When a pane's content (a file, launch output, or a terminal screen) was last loaded, and whether it is loading. */
data class ContentFreshness(
    val loadedAtEpochMillis: Long = 0L,
    val refreshState: ContentRefreshState = ContentRefreshState.Idle,
) {
    val hasLoadedOnce: Boolean
        get() = loadedAtEpochMillis > 0L

    fun isFresh(nowEpochMillis: Long): Boolean =
        hasLoadedOnce && nowEpochMillis - loadedAtEpochMillis <= CONTENT_STALE_MILLIS

    fun isStale(nowEpochMillis: Long): Boolean = !isFresh(nowEpochMillis)

    fun isRefreshing(): Boolean =
        refreshState == ContentRefreshState.Loading || refreshState == ContentRefreshState.Refreshing

    companion object {
        fun loadedAt(epochMillis: Long): ContentFreshness = ContentFreshness(loadedAtEpochMillis = epochMillis)
    }
}

/** Whether a load shows a blocking loader (nothing to show yet) or refreshes content already on screen. */
enum class ContentRequestKind {
    InitialLoad,
    Refresh,
    ;

    val refreshState: ContentRefreshState
        get() = when (this) {
            InitialLoad -> ContentRefreshState.Loading
            Refresh -> ContentRefreshState.Refreshing
        }
}

enum class ContentRefreshState {
    Idle,
    Loading,
    Refreshing,
    Failed,
}

/** Content under [key] needs a load: never loaded, or stale and not already loading. */
fun Map<String, ContentFreshness>.needsRefresh(key: String, nowEpochMillis: Long): Boolean {
    val freshness = this[key] ?: return true
    return !freshness.isRefreshing() && freshness.isStale(nowEpochMillis)
}

fun Map<String, ContentFreshness>.canStartRefresh(key: String): Boolean = this[key]?.isRefreshing() != true

/** A load of [key] started; its last load time is kept so a failure leaves the content stale, not fresh. */
fun Map<String, ContentFreshness>.withRefreshStarted(
    key: String,
    kind: ContentRequestKind,
): Map<String, ContentFreshness> =
    this + (key to ContentFreshness(this[key]?.loadedAtEpochMillis ?: 0L, kind.refreshState))

fun Map<String, ContentFreshness>.withLoaded(key: String, epochMillis: Long): Map<String, ContentFreshness> =
    this + (key to ContentFreshness.loadedAt(epochMillis))

fun Map<String, ContentFreshness>.withRefreshFailed(key: String): Map<String, ContentFreshness> =
    this + (key to (this[key] ?: ContentFreshness()).copy(refreshState = ContentRefreshState.Failed))

/** Drops an in-flight marker for content that is no longer shown; content never loaded is forgotten entirely. */
fun Map<String, ContentFreshness>.withClearedRefresh(key: String): Map<String, ContentFreshness> {
    val previous = this[key] ?: return this
    if (!previous.hasLoadedOnce) return this - key
    return this + (key to previous.copy(refreshState = ContentRefreshState.Idle))
}

fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
