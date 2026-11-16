package app.tennyo.mobile.design.theme

import androidx.compose.ui.unit.dp

object ComponentSizes {
    val buttonSmallHorizontalPadding = 12.dp
    val buttonMediumHorizontalPadding = 16.dp
    val primaryActionHorizontalPadding = 24.dp

    val buttonSmallHeight = 28.dp
    val buttonMediumHeight = 36.dp
    val primaryActionHeight = 44.dp
    val buttonLoadingIndicator = 14.dp

    val icon = 20.dp
    val compactIcon = 16.dp
    val selectionControl = 18.dp
    val iconButton = 36.dp

    /** Stroke width of the ring drawn around a selected selection control (radio/checkbox). */
    val selectionControlSelectedRingWidth = 5.dp

    val switchWidth = 40.dp
    val switchHeight = 24.dp
    /** Diameters of the switch's arc thumb: smaller off than on, so the state reads by shape as well as colour. */
    val switchThumbUnchecked = 12.dp
    val switchThumbChecked = 18.dp
    val switchThumbStroke = 2.5.dp

    val appMarkGlyph = 14.dp
    /** The orbiting mark that heads Home in place of a top bar. */
    val homeMark = 56.dp
    /** The smallest comfortable tap target, for a standalone control that has no row around it. */
    val touchTarget = 48.dp
    /** The Home connect button: a large FAB, taller than Material's 56dp default. */
    val largeFab = 72.dp

    val projectAvatar = 38.dp
    val actionTileHeight = 76.dp

    /** The widest a single-column form grows on tablets and in landscape. */
    val formMaxWidth = 460.dp
    val remoteConnectionGuideIconContainer = 48.dp
    val statusDot = 6.dp
    val segmentedControlPadding = 3.dp
    val segmentedControlItemSpacing = 2.dp
    val iconTopAlignmentPadding = 1.dp
    val editorTabAddWidth = 34.dp
    val dropdownMenuWidth = 200.dp
    val avatar = 32.dp
    val accessoryKeyIndicator = 5.dp
    val accessoryKeyMinWidth = 40.dp
    val accessoryKeyHeight = 34.dp
    val spinner = 28.dp

    /** A step's or diff's output scrolls inside this height, so opening one never pushes the screen's content away. */
    val outputMaxHeight = 240.dp
}

object Elevations {
    val none = 0.dp
    val subtle = 1.dp
}

object ComponentAlphas {
    const val visible = 1f
    const val slightlyTransparent = 0.8f
    const val disabledContent = 0.6f
    const val selectedContainer = 0.5f
    const val statusContainer = 0.1f

    /** A faint line in the content colour: a danger button's outline, the orbit a busy button's moon travels. */
    const val faintLine = 0.32f
}
