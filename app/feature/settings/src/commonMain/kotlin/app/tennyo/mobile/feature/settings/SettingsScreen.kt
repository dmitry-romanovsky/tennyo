package app.tennyo.mobile.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoBottomNavigation
import app.tennyo.mobile.design.TennyoBottomNavigationItem
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.TennyoSegmentedTabs
import app.tennyo.mobile.design.TennyoSwitch
import app.tennyo.mobile.design.layout.PlatformTopAppBar
import app.tennyo.mobile.design.layout.PlatformTopAppBarSize
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.settings.generated.resources.Res
import app.tennyo.mobile.feature.settings.generated.resources.settings_about
import app.tennyo.mobile.feature.settings.generated.resources.settings_about_action
import app.tennyo.mobile.feature.settings.generated.resources.settings_appearance
import app.tennyo.mobile.feature.settings.generated.resources.settings_color_scheme
import app.tennyo.mobile.feature.settings.generated.resources.settings_editor
import app.tennyo.mobile.feature.settings.generated.resources.settings_editor_soft_wrap
import app.tennyo.mobile.feature.settings.generated.resources.settings_git
import app.tennyo.mobile.feature.settings.generated.resources.settings_hotkeys
import app.tennyo.mobile.feature.settings.generated.resources.settings_theme
import app.tennyo.mobile.feature.settings.generated.resources.settings_title
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    component: SettingsComponent,
    appVersionName: String,
    modifier: Modifier = Modifier,
    hotkeysPage: @Composable () -> Unit,
    gitPage: (@Composable () -> Unit)? = null,
) {
    val state by component.state.collectAsState()
    val pages = SettingsPage.entries.filter { it != SettingsPage.Git || gitPage != null }
    var selectedPageName by rememberSaveable { mutableStateOf(SettingsPage.Appearance.name) }
    val selectedPage = pages.firstOrNull { it.name == selectedPageName } ?: SettingsPage.Appearance

    Scaffold(
        modifier = modifier,
        topBar = {
            PlatformTopAppBar(
                title = stringResource(Res.string.settings_title),
                onBack = component::onBack,
                actions = {
                    if (selectedPage != SettingsPage.About) {
                        TennyoIconButton(
                            icon = TennyoIcons.Info,
                            contentDescription = stringResource(Res.string.settings_about_action),
                            onClick = { selectedPageName = SettingsPage.About.name },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                size = PlatformTopAppBarSize.Compact,
                bottomContent = null,
            )
        },
        bottomBar = {
            TennyoBottomNavigation(
                items = pages.map { page ->
                    TennyoBottomNavigationItem(stringResource(page.title), vectorResource(page.icon))
                },
                selectedIndex = pages.indexOf(selectedPage),
                onSelected = { selectedPageName = pages[it].name },
            )
        },
        contentWindowInsets = WindowInsets(),
    ) { innerPadding ->
        SettingsScrollableList(
            innerPadding = innerPadding,
            contentKey = selectedPage,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (selectedPage) {
                SettingsPage.Appearance -> AppearanceSettingsPanel(state, component)
                SettingsPage.Hotkeys -> hotkeysPage()
                SettingsPage.Git -> gitPage?.invoke()

                SettingsPage.About -> SettingsAboutPage(appVersionName)
            }
        }
    }
}

@Composable
private fun SettingsScrollableList(
    innerPadding: PaddingValues,
    contentKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The bottom navigation owns the system-bar inset; the keyboard, which covers it, pads only what lies beyond it.
    LazyColumn(
        modifier = modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
        contentPadding = PaddingValues(
            start = Spacing.extraLarge,
            top = Spacing.large,
            end = Spacing.extraLarge,
            bottom = Spacing.large,
        ),
    ) {
        item(key = contentKey) {
            content()
        }
    }
}

private enum class SettingsPage(val title: StringResource, val icon: DrawableResource) {
    Appearance(Res.string.settings_appearance, TennyoIcons.Drawables.Palette),
    Hotkeys(Res.string.settings_hotkeys, TennyoIcons.Drawables.Keyboard),
    Git(Res.string.settings_git, TennyoIcons.Drawables.AccountTree),
    About(Res.string.settings_about, TennyoIcons.Drawables.Brand),
}

@Composable
private fun AppearanceSettingsPanel(
    state: AppSettingsState,
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(modifier) {
        SettingsSubsectionTitle(stringResource(Res.string.settings_theme))
        val themeModes = AppThemeMode.entries
        TennyoSegmentedTabs(
            items = themeModes.map { it.displayName() },
            selectedIndex = themeModes.indexOf(state.themeMode),
            onSelected = { component.onIntent(SettingsIntent.ThemeModeChanged(themeModes[it])) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
        )
        SettingsSubsectionTitle(stringResource(Res.string.settings_color_scheme))
        val colorSchemes = state.availableColorSchemes
        TennyoSegmentedTabs(
            items = colorSchemes.map { it.displayName() },
            selectedIndex = colorSchemes.indexOf(state.colorScheme),
            onSelected = { component.onIntent(SettingsIntent.ColorSchemeChanged(colorSchemes[it])) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
        )
        SettingsSubsectionTitle(stringResource(Res.string.settings_editor))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_editor_soft_wrap),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TennyoSwitch(
                checked = state.editorSoftWrap,
                onCheckedChange = { component.onIntent(SettingsIntent.EditorSoftWrapChanged(it)) },
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Corners.sm)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

@Composable
private fun SettingsSubsectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(
            start = Spacing.large,
            top = Spacing.large,
            end = Spacing.large,
            bottom = Spacing.small,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true, widthDp = 280, heightDp = 40)
@Composable
private fun SettingsSubsectionTitlePreview() {
    TennyoTheme {
        SettingsSubsectionTitle(text = "Color scheme")
    }
}
