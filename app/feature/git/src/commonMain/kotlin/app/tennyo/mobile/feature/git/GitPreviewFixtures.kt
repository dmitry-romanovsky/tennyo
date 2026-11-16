package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.AppThemeMode
import app.tennyo.mobile.design.theme.TennyoTheme

@Composable
internal fun GitScreenPreview(
    transform: (GitState) -> GitState = { it },
    content: @Composable (
        git: GitState,
        repository: GitRepository,
        onIntent: (GitIntent) -> Unit,
        modifier: Modifier,
    ) -> Unit,
) {
    TennyoTheme(themeMode = AppThemeMode.Auto) {
        val git = remember { transform(previewGitState()) }
        val repository = requireNotNull(
            git.overview?.repositories?.firstOrNull { it.id == git.selectedRepositoryId },
        )
        content(git, repository, {}, Modifier.fillMaxSize())
    }
}

internal val gitPreviewContentPadding = PaddingValues(16.dp)
