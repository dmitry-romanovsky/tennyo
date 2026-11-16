package app.tennyo.mobile.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tennyo.contract.PairingWords
import app.tennyo.mobile.design.TennyoButton
import app.tennyo.mobile.design.TennyoButtonSize
import app.tennyo.mobile.design.TennyoButtonStyle
import app.tennyo.mobile.design.resolve
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.generated.resources.Res
import app.tennyo.mobile.generated.resources.pairing_clear_selection
import app.tennyo.mobile.generated.resources.pairing_confirming
import app.tennyo.mobile.generated.resources.pairing_continue
import app.tennyo.mobile.generated.resources.pairing_description
import app.tennyo.mobile.generated.resources.pairing_tap_prompt
import app.tennyo.mobile.generated.resources.pairing_tile_selected
import app.tennyo.mobile.generated.resources.pairing_title
import app.tennyo.mobile.link.PairingConfirmation
import org.jetbrains.compose.resources.stringResource

private const val PAIRING_TILES_PER_ROW = 4
private val PAIRING_TILE_MIN_HEIGHT = 48.dp

/**
 * The tap-to-confirm pairing step: the companion shows four ordered words, this grid shows sixteen, and the user taps
 * the four in order.
 *
 * Tiles are laid out in fixed rows of [PAIRING_TILES_PER_ROW] with equal weight rather than a flow layout, so
 * variable-length words cannot produce ragged rows on a screen where predictability matters more than density.
 */
@Composable
internal fun PairingPanel(
    confirmation: PairingConfirmation,
    onToggleWord: (String) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.extraLarge),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource(Res.string.pairing_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.pairing_description, PairingWords.SECRET_LENGTH),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )

                PairingSelectionSummary(
                    selectedWords = confirmation.selectedWords,
                    modifier = Modifier.fillMaxWidth(),
                )

                PairingWordGrid(
                    grid = confirmation.wordGrid,
                    selectedWords = confirmation.selectedWords,
                    enabled = !confirmation.isConfirming,
                    onToggleWord = onToggleWord,
                    modifier = Modifier.fillMaxWidth(),
                )

                confirmation.issue?.let { issue ->
                    Text(
                        text = issue.text.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    TennyoButton(
                        label = stringResource(Res.string.pairing_clear_selection),
                        onClick = onClear,
                        enabled = confirmation.selectedWords.isNotEmpty() && !confirmation.isConfirming,
                        style = TennyoButtonStyle.Text,
                        size = TennyoButtonSize.PrimaryAction,
                    )
                    TennyoButton(
                        label = if (confirmation.isConfirming) {
                            stringResource(Res.string.pairing_confirming)
                        } else {
                            stringResource(Res.string.pairing_continue)
                        },
                        onClick = onConfirm,
                        enabled = confirmation.isComplete && !confirmation.isConfirming,
                        modifier = Modifier.weight(1f),
                        size = TennyoButtonSize.PrimaryAction,
                        loading = confirmation.isConfirming,
                    )
                }
            }
        }
    }
}

/** Shows the taps in order so the user can check them against the IDE before spending an attempt. */
@Composable
private fun PairingSelectionSummary(
    selectedWords: List<String>,
    modifier: Modifier = Modifier,
) {
    Text(
        text = selectedWords.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
            ?: stringResource(Res.string.pairing_tap_prompt),
        color = if (selectedWords.isEmpty()) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun PairingWordGrid(
    grid: List<String>,
    selectedWords: List<String>,
    enabled: Boolean,
    onToggleWord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        grid.chunked(PAIRING_TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                row.forEach { word ->
                    PairingWordTile(
                        word = word,
                        selectionOrder = selectedWords.indexOf(word).takeIf { it >= 0 }?.plus(1),
                        enabled = enabled,
                        onClick = { onToggleWord(word) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingWordTile(
    word: String,
    selectionOrder: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectionOrder != null
    val selectionDescription = selectionOrder?.let { stringResource(Res.string.pairing_tile_selected, it) }
    val shape = Corners.sm
    Box(
        modifier = modifier
            .heightIn(min = PAIRING_TILE_MIN_HEIGHT)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = ComponentAlphas.selectedContainer)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (selected) {
                    Modifier.border(Borders.regular, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            // The word itself is already announced by the Text below, so only the selection state is described here.
            // Repeating the word as a contentDescription would have it read twice.
            .semantics {
                selectionDescription?.let { stateDescription = it }
            }
            .padding(horizontal = Spacing.small, vertical = Spacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
