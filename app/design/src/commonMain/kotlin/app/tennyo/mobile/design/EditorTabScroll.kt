package app.tennyo.mobile.design

/** Where the editor tab row is scrolled and where the selected tab sits in it, in pixels. */
internal data class EditorTabScroll(
    val currentScroll: Int,
    val viewportWidth: Int,
    val selectedTabStart: Int,
    val selectedTabEnd: Int,
    val maxScroll: Int,
) {
    /** The scroll showing the selected tab: kept while it is fully visible, else the tab's start at the row's start. */
    fun target(): Int {
        val maxScroll = maxScroll.coerceAtLeast(0)
        val currentScroll = currentScroll.coerceIn(0, maxScroll)
        if (viewportWidth <= 0 || selectedTabEnd <= selectedTabStart) return currentScroll
        val clipped = selectedTabStart < currentScroll || selectedTabEnd > currentScroll + viewportWidth
        return if (clipped) selectedTabStart.coerceIn(0, maxScroll) else currentScroll
    }
}
