package app.tennyo.mobile.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.copy_full_error
import app.tennyo.mobile.design.generated.resources.full_error_copied
import app.tennyo.mobile.design.generated.resources.full_error_title
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.TennyoTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun ErrorDetailsSheet(
    errorText: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(Res.string.full_error_title), style = MaterialTheme.typography.titleLarge)
            // The button confirms the copy itself, rather than a toast over the sheet.
            var copied by remember(errorText) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(2.seconds)
                    copied = false
                }
            }
            TennyoButton(
                label = stringResource(if (copied) Res.string.full_error_copied else Res.string.copy_full_error),
                onClick = {
                    onCopy()
                    copied = true
                },
                style = TennyoButtonStyle.Text,
                size = TennyoButtonSize.Small,
            )
        }
        SelectionContainer {
            Text(
                text = errorText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun ErrorDetailsSheetPreview() {
    TennyoTheme {
        ErrorDetailsSheet(
            errorText = "java.net.SocketTimeoutException: failed to connect to localhost/127.0.0.1 " +
                "(port 8080) after 15000ms\n" +
                "\tat okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt)\n" +
                "\tat okio.Buffer.write(Buffer.kt)",
            onCopy = {},
        )
    }
}
