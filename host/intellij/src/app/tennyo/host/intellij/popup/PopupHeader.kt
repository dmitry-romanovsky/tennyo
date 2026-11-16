package app.tennyo.host.intellij.popup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.intellij.HostBundle
import app.tennyo.host.intellij.HostIcons
import app.tennyo.host.intellij.PopupState
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon as SwingIcon

@Composable
internal fun PopupHeader(
    state: PopupState,
    modifier: Modifier = Modifier,
) {
    val pluginIconBitmap = rememberPluginIconBitmap()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = pluginIconBitmap,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = HostBundle.message("popup.title"),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = state.projectPath?.let(::projectPathLabel)?.takeIf { it.isNotBlank() } ?: state.projectName,
            color = popupMutedTextColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = JewelTheme.defaultTextStyle.fontSize - 2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun projectPathLabel(projectPath: String): String =
    projectPath
        .substringAfterLast('/')
        .substringAfterLast('\\')

@Composable
private fun rememberPluginIconBitmap(): ImageBitmap =
    remember {
        swingIconToBufferedImage(HostIcons.host, scale = 2f).toComposeImageBitmap()
    }

private fun swingIconToBufferedImage(icon: SwingIcon, scale: Float = 1f): BufferedImage {
    val image = ImageUtil.createImage(
        (icon.iconWidth * scale).toInt(),
        (icon.iconHeight * scale).toInt(),
        BufferedImage.TYPE_INT_ARGB,
    )
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.scale(scale.toDouble(), scale.toDouble())
        icon.paintIcon(null, graphics, 0, 0)
    } finally {
        graphics.dispose()
    }
    return image
}
