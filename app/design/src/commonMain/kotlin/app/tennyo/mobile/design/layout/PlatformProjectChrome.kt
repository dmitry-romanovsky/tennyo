package app.tennyo.mobile.design.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

@Composable
fun PlatformTopAppBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    size: PlatformTopAppBarSize = PlatformTopAppBarSize.Compact,
    bottomContent: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
) {
    val topAppBarBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    Surface(
        modifier = modifier,
        color = containerColor,
    ) {
        Column {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.large, vertical = Spacing.small),
                shape = Corners.xl,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(Borders.regular, topAppBarBorderColor),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (size == PlatformTopAppBarSize.Large) 56.dp else 52.dp)
                            .padding(horizontal = 4.dp),
                    ) {
                        PlatformTopAppBarNavigationIcon(
                            onBack = onBack,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                        Text(
                            text = title,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 72.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actions()
                        }
                    }
                    bottomContent?.let {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            it()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformTopAppBarNavigationIcon(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBack,
        modifier = modifier,
    ) {
        Icon(
            imageVector = TennyoIcons.ArrowBack,
            contentDescription = null,
        )
    }
}
