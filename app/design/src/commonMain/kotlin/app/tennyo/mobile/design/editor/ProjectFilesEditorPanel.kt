package app.tennyo.mobile.design.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.ClosableScrollableTabRow
import app.tennyo.mobile.design.TabCloseMode
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.layout.PaneTabStrip
import app.tennyo.mobile.design.layout.paneInset
import app.tennyo.mobile.design.layout.safeDrawingBottomInset
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

@Composable
fun <T> ProjectFilesEditorPanel(
    tabs: List<T>,
    selectedTabIndex: Int,
    itemKey: (T) -> Any,
    title: (T) -> String,
    onSelectTab: (index: Int, item: T) -> Unit,
    onCloseTab: (T) -> Unit,
    onNavigatorOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keyboardVisible: Boolean = false,
    contentHorizontalPadding: Dp = Spacing.large,
    bottomContentInset: Dp? = null,
    closeDrawerSignal: Any? = null,
    isDirty: (T) -> Boolean = { false },
    closeMode: TabCloseMode = TabCloseMode.SelectedOnly,
    editorActions: (@Composable RowScope.() -> Unit)? = null,
    openNavigatorContentDescription: String,
    editorContent: @Composable BoxScope.() -> Unit,
) {
    val resolvedBottomInset = if (keyboardVisible) {
        0.dp
    } else {
        bottomContentInset ?: safeDrawingBottomInset()
    }
    LaunchedEffect(closeDrawerSignal) {
        if (closeDrawerSignal != null) {
            onNavigatorOpenChange(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = resolvedBottomInset),
    ) {
        AnimatedVisibility(visible = !keyboardVisible) {
            key(tabs.size) {
                PaneTabStrip {
                    if (tabs.isNotEmpty()) {
                        ClosableScrollableTabRow(
                            items = tabs,
                            selectedIndex = selectedTabIndex,
                            itemKey = itemKey,
                            title = title,
                            isDirty = isDirty,
                            onSelect = onSelectTab,
                            onClose = onCloseTab,
                            closeMode = closeMode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        // The editor and its action bar are one card: the editor keeps the rounded top, the bar the rounded
        // bottom, and the edge they share stays square so they read as a single surface (screen 03). The bar always
        // carries the files button at its start, where the thumb is, so it is never lost among the open tabs.
        val outline = MaterialTheme.colorScheme.outlineVariant
        // Under the tabs the editor's top edge is square, joining the strip; with no tabs it rounds itself.
        val editorShape = if (tabs.isNotEmpty() && !keyboardVisible) RectangleShape else Corners.panelTop
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .paneInset()
                .clip(editorShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(width = Borders.regular, color = outline, shape = editorShape),
            content = editorContent,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paneInset()
                .padding(bottom = Spacing.medium)
                .clip(Corners.panelBottom)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(Borders.regular, outline, Corners.panelBottom)
                .padding(horizontal = contentHorizontalPadding, vertical = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TennyoIconButton(
                icon = TennyoIcons.FolderOpen,
                contentDescription = openNavigatorContentDescription,
                onClick = { onNavigatorOpenChange(true) },
            )
            editorActions?.invoke(this)
        }
    }
}
