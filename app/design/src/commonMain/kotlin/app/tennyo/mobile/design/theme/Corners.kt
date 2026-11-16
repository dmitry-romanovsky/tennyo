package app.tennyo.mobile.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object Corners {
    val none = RoundedCornerShape(0.dp)
    val xs = RoundedCornerShape(6.dp)
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(24.dp)
    val xl = RoundedCornerShape(32.dp)
    val full = RoundedCornerShape(50)

    /** Alias for [sm] — used for card surfaces. */
    val card = sm

    /** Alias for [lg] — used for large sheet/panel surfaces. */
    val panel = lg

    /** A panel surface that another bar closes off below it, so only its top corners are rounded. */
    val panelTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    /** A panel surface that another bar opens above it, so only its bottom corners are rounded. */
    val panelBottom = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
}
