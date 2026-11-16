package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test

class PanePaddingTest {
    @Test
    fun keepsScaffoldSidesAndUsesSystemBarInsetWhenItIsLarger() {
        val padding = panePadding(
            innerPadding = PaddingValues(start = 6.dp, top = 18.dp, end = 10.dp, bottom = 4.dp),
            layoutDirection = LayoutDirection.Ltr,
            bottomSystemBarInset = 22.dp,
        )

        assertEquals(6.dp, padding.contentPadding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(18.dp, padding.contentPadding.calculateTopPadding())
        assertEquals(10.dp, padding.contentPadding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(22.dp, padding.contentPadding.calculateBottomPadding())
        assertEquals(22.dp, padding.bottomSafeInset)
        assertEquals(Spacing.large, padding.contentHorizontalPadding)
    }

    @Test
    fun keepsScaffoldBottomPaddingWhenItExceedsSystemBarInset() {
        val padding = panePadding(
            innerPadding = PaddingValues(top = 16.dp, bottom = 14.dp),
            layoutDirection = LayoutDirection.Ltr,
            bottomSystemBarInset = 10.dp,
        )

        assertEquals(16.dp, padding.contentPadding.calculateTopPadding())
        assertEquals(14.dp, padding.contentPadding.calculateBottomPadding())
        assertEquals(14.dp, padding.bottomSafeInset)
        assertEquals(Spacing.large, padding.contentHorizontalPadding)
    }
}
