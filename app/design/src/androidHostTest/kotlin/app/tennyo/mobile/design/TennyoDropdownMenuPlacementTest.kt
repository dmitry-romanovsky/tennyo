package app.tennyo.mobile.design

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TennyoDropdownMenuPlacementTest {

    @Test
    fun explicitMaxHeightIsRespectedWhenRoomIsAvailable() {
        val placement = resolveDropdownMenuPlacement(
            anchorBounds = IntRect(left = 24, top = 120, right = 200, bottom = 160),
            explicitMaxHeightPx = 240,
            windowHeightPx = 900,
            verticalMarginPx = 12,
        )

        assertEquals(240, placement.maxHeightPx)
        assertFalse(placement.showAbove)
    }

    @Test
    fun explicitMaxHeightIsClippedToWindowSpace() {
        val placement = resolveDropdownMenuPlacement(
            anchorBounds = IntRect(left = 24, top = 40, right = 200, bottom = 80),
            explicitMaxHeightPx = 280,
            windowHeightPx = 180,
            verticalMarginPx = 12,
        )

        assertEquals(88, placement.maxHeightPx)
        assertFalse(placement.showAbove)
    }

    @Test
    fun defaultPlacementUsesTheLargerVisibleSide() {
        val placement = resolveDropdownMenuPlacement(
            anchorBounds = IntRect(left = 24, top = 720, right = 200, bottom = 760),
            explicitMaxHeightPx = null,
            windowHeightPx = 960,
            verticalMarginPx = 16,
        )

        assertEquals(704, placement.maxHeightPx)
        assertTrue(placement.showAbove)
    }

    @Test
    fun anchorNearTopUsesWindowSpaceBelow() {
        val placement = resolveDropdownMenuPlacement(
            anchorBounds = IntRect(left = 24, top = 120, right = 200, bottom = 160),
            explicitMaxHeightPx = null,
            windowHeightPx = 900,
            verticalMarginPx = 12,
        )

        assertEquals(728, placement.maxHeightPx)
        assertFalse(placement.showAbove)
    }
}
