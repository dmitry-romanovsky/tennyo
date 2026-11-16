package app.tennyo.mobile.design

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorTabScrollTest {
    @Test
    fun visibleTabKeepsScroll() {
        assertEquals(80, target(currentScroll = 80, tabStart = 100, tabEnd = 180))
    }

    @Test
    fun tabClippedAtStartScrollsToIt() {
        assertEquals(96, target(currentScroll = 120, tabStart = 96, tabEnd = 180))
    }

    @Test
    fun tabClippedAtEndScrollsToItsStart() {
        assertEquals(190, target(currentScroll = 40, tabStart = 190, tabEnd = 280))
    }

    @Test
    fun targetStaysWithinScrollRange() {
        assertEquals(0, target(currentScroll = 40, tabStart = -24, tabEnd = 48))
        assertEquals(300, target(currentScroll = 160, tabStart = 340, tabEnd = 430, maxScroll = 300))
    }

    @Test
    fun unmeasuredLayoutKeepsScroll() {
        assertEquals(40, target(currentScroll = 40, tabStart = 190, tabEnd = 280, viewportWidth = 0))
        assertEquals(40, target(currentScroll = 40, tabStart = 190, tabEnd = 190))
    }

    private fun target(
        currentScroll: Int,
        tabStart: Int,
        tabEnd: Int,
        viewportWidth: Int = 160,
        maxScroll: Int = 400,
    ): Int = EditorTabScroll(
        currentScroll = currentScroll,
        viewportWidth = viewportWidth,
        selectedTabStart = tabStart,
        selectedTabEnd = tabEnd,
        maxScroll = maxScroll,
    ).target()
}
