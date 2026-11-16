package app.tennyo.mobile.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tennyo.mobile.design.TennyoIconButton
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.feature.git.generated.resources.Res
import app.tennyo.mobile.feature.git.generated.resources.git_back_to_commit
import app.tennyo.mobile.feature.git.generated.resources.git_close
import app.tennyo.mobile.feature.git.generated.resources.git_commit
import app.tennyo.mobile.feature.git.generated.resources.git_copy_commit_hash
import org.jetbrains.compose.resources.stringResource

/**
 * The title row of the commit-and-diff sheet: a back arrow only when there is somewhere to go back to (a commit's
 * file returns to the commit), the title, the page's own [actions], and the round close button every sheet ends with.
 */
@Composable
internal fun GitSheetHeader(
    title: String,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleFontFamily: FontFamily? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        if (onBack != null) {
            TennyoIconButton(
                icon = TennyoIcons.ArrowBack,
                contentDescription = stringResource(Res.string.git_back_to_commit),
                onClick = onBack,
                iconSize = ComponentSizes.icon,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Spacer(Modifier.width(Spacing.small))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
        actions()
        TennyoIconButton(
            icon = TennyoIcons.Close,
            contentDescription = stringResource(Res.string.git_close),
            onClick = onClose,
            iconSize = ComponentSizes.icon,
            contentColor = MaterialTheme.colorScheme.onSurface,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Preview(name = "Git sheet header", widthDp = 420, showBackground = true)
@Composable
private fun GitSheetHeaderPreview() {
    TennyoTheme {
        Surface {
            Column {
                GitSheetHeader(
                    title = "UserService.kt",
                    subtitle = "src/main/kotlin/app/users",
                    onBack = {},
                    onClose = {},
                )
                GitSheetHeader(
                    title = stringResource(Res.string.git_commit),
                    onBack = null,
                    onClose = {},
                    actions = {
                        TennyoIconButton(
                            icon = TennyoIcons.ContentCopy,
                            contentDescription = stringResource(Res.string.git_copy_commit_hash),
                            onClick = {},
                        )
                    },
                )
            }
        }
    }
}
