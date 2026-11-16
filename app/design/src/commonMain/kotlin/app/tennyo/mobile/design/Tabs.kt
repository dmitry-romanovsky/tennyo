package app.tennyo.mobile.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.lerp
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Elevations
import app.tennyo.mobile.design.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

data class TennyoEditorTab(
    val title: String,
    /** What the tab holds — a file's kind, a terminal, a run — drawn before the title. */
    val icon: ImageVector? = null,
    /** The tab's work is running — a run, a build: a spinner takes the icon's place until it stops. */
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val enabled: Boolean = true,
    val key: Any = title,
)

@Composable
fun TennyoSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    itemKeys: List<Any> = List(items.size) { it },
) {
    // An outlined group whose selected segment carries an accent border on a tinted fill, like IntelliJ's build
    // system chooser.
    val shape = Corners.sm
    Row(
        modifier = modifier
            .selectableGroup()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(Borders.thin, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(ComponentSizes.segmentedControlPadding),
        horizontalArrangement = Arrangement.spacedBy(ComponentSizes.segmentedControlItemSpacing),
    ) {
        items.forEachIndexed { index, label ->
            key(itemKeys.getOrElse(index) { index }) {
                TennyoSegmentedTab(
                    label = label,
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    enabled = enabled(index),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}


/**
 * Renders the compact editor tab strip used by file, terminal, and run surfaces.
 *
 * [leadingAction] and [trailingAction] are part of the strip chrome: callers must render them with
 * [TennyoTabStripButton], not a general-purpose button or [TennyoIconButton], so their width, vertical
 * padding, hit target, and surface treatment remain identical across every editor tab strip.
 */
@Composable
fun TennyoEditorTabs(
    tabs: List<TennyoEditorTab>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClose: ((Int) -> Unit)? = null,
    closeContentDescription: (TennyoEditorTab) -> String = { "Close tab ${it.title}" },
    closeMode: TabCloseMode = TabCloseMode.SelectedOnly,
    tabStatus: (@Composable RowScope.(Int) -> Unit)? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var selectedTabLayout by remember { mutableStateOf<EditorTabLayoutInfo?>(null) }

    LaunchedEffect(selectedIndex, viewportWidthPx, scrollState.maxValue == 0) {
        // The close boxes reshape the pills as selection moves; scroll once they settle rather than restarting the
        // scroll on every frame of that animation.
        delay(AnimationDurations.regularMillis.milliseconds)
        val layout = selectedTabLayout?.takeIf { it.index == selectedIndex } ?: return@LaunchedEffect
        // Skip while content is unmeasured; a zero maxValue would clamp the target to 0
        // and destroy the existing scroll position mid-recomposition.
        if (scrollState.maxValue == 0) return@LaunchedEffect
        val target = EditorTabScroll(
            currentScroll = scrollState.value,
            viewportWidth = viewportWidthPx,
            selectedTabStart = layout.start,
            selectedTabEnd = layout.end,
            maxScroll = scrollState.maxValue,
        ).target()
        if (target != scrollState.value) {
            scrollState.animateScrollTo(target, tween(AnimationDurations.quickMillis))
        }
    }

    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingAction?.invoke()
        Row(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { viewportWidthPx = it.width }
                .horizontalScroll(scrollState)
                .selectableGroup()
                .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                key(tab.key) {
                    val selected = selectedIndex == index
                    // Each tab is a pill: the selected one filled with the accent tint, the rest only outlined, so
                    // the strip reads like the destination pills above it.
                    val containerColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        animationSpec = tween(AnimationDurations.quickMillis),
                        label = "EditorTabContainer",
                    )
                    val borderColor by animateColorAsState(
                        targetValue = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = tween(AnimationDurations.quickMillis),
                        label = "EditorTabBorder",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(IntrinsicSize.Max)
                            .then(
                                if (selected) {
                                    Modifier.onPlaced { coordinates ->
                                        val start = coordinates.positionInParent().x.roundToInt()
                                        selectedTabLayout = EditorTabLayoutInfo(
                                            index = index,
                                            start = start,
                                            end = start + coordinates.size.width,
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .clip(Corners.full)
                            .background(containerColor)
                            .border(Borders.regular, borderColor, Corners.full)
                            .selectable(
                                selected = selected,
                                enabled = tab.enabled,
                                role = Role.Tab,
                                onClick = { onSelected(index) },
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.padding(
                                    start = Spacing.medium,
                                    top = Spacing.extraSmall,
                                    bottom = Spacing.extraSmall,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tab.busy) {
                                    TennyoProgressIndicator(indicatorSize = ComponentSizes.compactIcon)
                                } else tab.icon?.let { icon ->
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(ComponentSizes.compactIcon),
                                        tint = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        !tab.enabled ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = ComponentAlphas.disabledContent)
                                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AnimatedVisibility(
                                    visible = tab.dirty,
                                    enter = expandIn(animationSpec = tween(AnimationDurations.quickMillis)),
                                    exit = shrinkOut(animationSpec = tween(AnimationDurations.quickMillis)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(ComponentSizes.statusDot)
                                            .clip(Corners.full)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                                tabStatus?.let { it(index) }
                            }
                            TabCloseButton(
                                visible = onClose != null && closeMode.shows(selected),
                                selected = selected,
                                contentDescription = closeContentDescription(tab),
                                onClick = { onClose?.invoke(index) },
                            )
                        }
                    }
                }
            }
        }
        trailingAction?.invoke()
    }
}

/**
 * A tab that cannot close has no close box, rather than an invisible one taking room. One progress value drives
 * both the slot's width and the glyph's fade and scale, so the pill reshapes in a single motion instead of two
 * visibility animations racing each other.
 */
@Composable
private fun TabCloseButton(
    visible: Boolean,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(AnimationDurations.regularMillis, easing = FastOutSlowInEasing),
        label = "TabCloseProgress",
    )
    // Hidden, the slot is just the pill's closing edge; shown, it holds the button between two small gaps.
    val slotWidth = lerp(Spacing.medium, ComponentSizes.buttonMediumHeight + Spacing.extraSmall * 2, progress)
    Box(
        modifier = modifier
            .width(slotWidth)
            .fillMaxHeight()
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .padding(start = Spacing.extraSmall)
                    .requiredSize(ComponentSizes.buttonMediumHeight)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = CLOSE_COLLAPSED_SCALE + (1f - CLOSE_COLLAPSED_SCALE) * progress
                        scaleY = scaleX
                    }
                    .clip(Corners.full)
                    .clickable(enabled = visible, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TennyoIcons.Close,
                    contentDescription = contentDescription.takeIf { visible },
                    modifier = Modifier.size(ComponentSizes.appMarkGlyph),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private const val CLOSE_COLLAPSED_SCALE = 0.5f

private data class EditorTabLayoutInfo(
    val index: Int,
    val start: Int,
    val end: Int,
)


@Composable
private fun TennyoSegmentedTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(AnimationDurations.regularMillis),
        label = "TennyoSegmentedTabContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(AnimationDurations.regularMillis),
        label = "TennyoSegmentedTabBorder",
    )
    val shape = Corners.xs
    Text(
        text = label,
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(Borders.regular, borderColor, shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .heightIn(min = ComponentSizes.buttonSmallHeight)
            .wrapContentHeight()
            .padding(horizontal = Spacing.small, vertical = Spacing.small),
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

enum class TabCloseMode {
    SelectedOnly,
    Always,

    /** No tab shows a close control: the host does not accept a remote close. */
    Never,
    ;

    fun shows(selected: Boolean): Boolean = when (this) {
        Always -> true
        SelectedOnly -> selected
        Never -> false
    }
}

data class TennyoBottomNavigationItem(
    val label: String,
    val icon: ImageVector,
    val key: Any = label,
)

/**
 * Top-level destination switcher for a screen with a few peer pages. It keeps the navigation bar's
 * default window insets, so it owns the bottom system-bar inset and content above it must not add it again.
 */
@Composable
fun TennyoBottomNavigation(
    items: List<TennyoBottomNavigationItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(thickness = Borders.regular, color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevations.none,
        ) {
            items.forEachIndexed { index, item ->
                key(item.key) {
                    val selected = index == selectedIndex
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelected(index) },
                        icon = { Icon(imageVector = item.icon, contentDescription = null) },
                        label = {
                            Text(
                                text = item.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun <T> ClosableScrollableTabRow(
    items: List<T>,
    selectedIndex: Int,
    itemKey: (T) -> Any,
    title: (T) -> String,
    onSelect: (index: Int, item: T) -> Unit,
    onClose: (T) -> Unit,
    modifier: Modifier = Modifier,
    isDirty: (T) -> Boolean = { false },
    closeMode: TabCloseMode = TabCloseMode.SelectedOnly,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    itemStatus: @Composable RowScope.(T) -> Unit = {},
) {
    if (items.isEmpty()) {
        if (leadingAction != null || trailingAction != null) {
            Row(modifier = modifier) {
                leadingAction?.invoke()
                trailingAction?.invoke()
            }
        }
        return
    }

    val safeSelectedIndex = selectedIndex.coerceIn(items.indices)
    TennyoEditorTabs(
        tabs = items.map { item ->
            val label = title(item)
            TennyoEditorTab(
                title = label,
                icon = fileTabIcon(label),
                dirty = isDirty(item),
                key = itemKey(item),
            )
        },
        selectedIndex = safeSelectedIndex,
        onSelected = { index -> onSelect(index, items[index]) },
        onClose = { index -> onClose(items[index]) },
        closeMode = closeMode,
        tabStatus = { index -> this.itemStatus(items[index]) },
        leadingAction = leadingAction,
        trailingAction = trailingAction,
        modifier = modifier,
    )
}

/** What a file tab holds, read from its name. */
enum class FileTabKind { SOURCE, CONFIGURATION, DOCUMENT, OTHER }

fun fileTabKind(name: String): FileTabKind {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        name.startsWith("build.gradle", ignoreCase = true) || extension in CONFIGURATION_EXTENSIONS ->
            FileTabKind.CONFIGURATION
        extension in DOCUMENT_EXTENSIONS -> FileTabKind.DOCUMENT
        extension in SOURCE_EXTENSIONS -> FileTabKind.SOURCE
        else -> FileTabKind.OTHER
    }
}

/** The icon a file tab shows for [name]. */
@Composable
fun fileTabIcon(name: String): ImageVector = when (fileTabKind(name)) {
    FileTabKind.SOURCE -> TennyoIcons.Code
    FileTabKind.CONFIGURATION -> TennyoIcons.Settings
    FileTabKind.DOCUMENT -> TennyoIcons.Description
    FileTabKind.OTHER -> TennyoIcons.InsertDriveFile
}

private val SOURCE_EXTENSIONS = setOf(
    "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "m",
    "rb", "php", "scala", "dart", "lua", "sh", "bash", "zsh", "ps1", "sql", "html", "css", "scss", "vue", "svelte",
)
private val CONFIGURATION_EXTENSIONS = setOf(
    "gradle", "json", "yaml", "yml", "toml", "xml", "properties", "ini", "cfg", "conf", "env", "lock",
)
private val DOCUMENT_EXTENSIONS = setOf("md", "markdown", "txt", "rst", "adoc", "log", "csv")


