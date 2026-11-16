package app.tennyo.mobile.feature.launch

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.panScroll
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import app.tennyo.mobile.design.theme.editorTextStyle
import kotlinx.coroutines.flow.collect

private val LaunchOutputMaxHeight = 560.dp

@Composable
internal fun LaunchOutputPanel(
    output: String,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    LaunchedEffect(Unit) {
        snapshotFlow { verticalScroll.maxValue }.collect { verticalScroll.scrollTo(it) }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.card,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SelectionContainer {
            Text(
                text = output,
                style = editorTextStyle().copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier
                    .heightIn(max = LaunchOutputMaxHeight)
                    .panScroll(verticalScroll, horizontalScroll)
                    .padding(Spacing.medium),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun LaunchOutputPanelPreview() {
    TennyoTheme {
        LaunchOutputPanel(
            output = "Starting Android app\n> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL\n",
        )
    }
}
