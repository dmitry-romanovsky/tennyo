package app.tennyo.mobile.design.editor

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorCaretVisibilityTest {
    @Test
    fun visibleCaretKeepsScroll() {
        val target = scrollTarget(
            caretLeft = 80f,
            contextTop = 120f,
            contextBottom = 180f,
            horizontalScroll = 40,
            verticalScroll = 100,
        )

        assertEquals(EditorCaretScrollTarget(horizontalScroll = 40, verticalScroll = 100), target)
    }

    @Test
    fun contextBelowViewportScrollsDown() {
        assertEquals(152, scrollTarget(contextTop = 260f, contextBottom = 340f, verticalScroll = 100).verticalScroll)
    }

    @Test
    fun contextAboveViewportScrollsUp() {
        assertEquals(28, scrollTarget(contextTop = 40f, contextBottom = 100f, verticalScroll = 120).verticalScroll)
    }

    @Test
    fun contextUnderKeyboardScrollsDown() {
        val visible = editorVisibleViewportSize(IntSize(200, 420), keyboardVisible = true, keyboardOverlapPx = 240)

        val target = scrollTarget(
            contextTop = 260f,
            contextBottom = 330f,
            viewportHeight = visible.height,
            verticalScroll = 24,
        )

        assertEquals(162, target.verticalScroll)
    }

    @Test
    fun caretRightOfViewportScrollsRight() {
        assertEquals(154, scrollTarget(caretLeft = 340f, horizontalScroll = 80).horizontalScroll)
    }

    @Test
    fun caretLeftOfViewportScrollsLeft() {
        assertEquals(28, scrollTarget(caretLeft = 40f, horizontalScroll = 100).horizontalScroll)
    }

    @Test
    fun targetStaysWithinScrollRange() {
        val down = scrollTarget(contextTop = 900f, contextBottom = 980f, verticalScroll = 200, maxVerticalScroll = 250)
        val left = scrollTarget(caretLeft = -40f, horizontalScroll = 20)

        assertEquals(250, down.verticalScroll)
        assertEquals(0, left.horizontalScroll)
    }

    @Test
    fun unmeasuredViewportOnlyClampsScroll() {
        val target = scrollTarget(contextTop = 900f, contextBottom = 980f, viewportHeight = 0, verticalScroll = 500)

        assertEquals(EditorCaretScrollTarget(horizontalScroll = 0, verticalScroll = 400), target)
    }

    @Test
    fun tapWithoutImeInsetStartsClosed() {
        assertTrue(editorTapStartsWithKeyboardClosed(keyboardVisible = true, imeBottomPx = 0))
    }

    @Test
    fun tapWithImeInsetStartsOpen() {
        assertFalse(editorTapStartsWithKeyboardClosed(keyboardVisible = true, imeBottomPx = 240))
    }

    @Test
    fun overlapShrinksVisibleViewport() {
        val visible = editorVisibleViewportSize(IntSize(200, 400), keyboardVisible = true, keyboardOverlapPx = 160)

        assertEquals(IntSize(200, 240), visible)
    }

    @Test
    fun hiddenKeyboardKeepsViewport() {
        val visible = editorVisibleViewportSize(IntSize(200, 400), keyboardVisible = false, keyboardOverlapPx = 160)

        assertEquals(IntSize(200, 400), visible)
    }

    @Test
    fun overlapIsMeasuredFromEditorBottom() {
        assertEquals(120, overlap(editorBottomInWindow = 720f))
    }

    @Test
    fun editorAboveKeyboardHasNoOverlap() {
        assertEquals(0, overlap(editorBottomInWindow = 560f))
    }

    @Test
    fun overlapNeverExceedsEditorHeight() {
        assertEquals(400, overlap(editorBottomInWindow = 1_200f))
    }

    private fun scrollTarget(
        caretLeft: Float = 120f,
        contextTop: Float = 140f,
        contextBottom: Float = 180f,
        viewportHeight: Int = 200,
        horizontalScroll: Int = 0,
        verticalScroll: Int = 0,
        maxVerticalScroll: Int = 400,
    ): EditorCaretScrollTarget = editorCaretScrollTarget(
        EditorCaretScrollInput(
            caretLeft = caretLeft,
            caretRight = caretLeft + 2f,
            contextTop = contextTop,
            contextBottom = contextBottom,
            viewportWidth = 200,
            viewportHeight = viewportHeight,
            currentHorizontalScroll = horizontalScroll,
            currentVerticalScroll = verticalScroll,
            maxHorizontalScroll = 400,
            maxVerticalScroll = maxVerticalScroll,
        ),
    )

    private fun overlap(editorBottomInWindow: Float): Int = editorKeyboardOverlapPx(
        layoutViewportHeight = 400,
        editorBottomInWindow = editorBottomInWindow,
        windowHeightPx = 900,
        imeBottomPx = 300,
    )
}
