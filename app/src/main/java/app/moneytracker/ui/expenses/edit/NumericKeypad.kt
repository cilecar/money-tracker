package app.moneytracker.ui.expenses.edit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.moneytracker.R
import app.moneytracker.ui.format.currentLocale
import java.text.DecimalFormatSymbols

/**
 * Своя клавиатура вместо системной: крупные кнопки, всегда на месте, нет лишних
 * символов, разделитель — по локали. Долгое нажатие на ⌫ стирает всё.
 */
@Composable
fun NumericKeypad(onKey: (KeypadKey) -> Unit, modifier: Modifier = Modifier, decimalEnabled: Boolean = true) {
    val separator = DecimalFormatSymbols.getInstance(currentLocale()).decimalSeparator.toString()
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(separator, "0", BACKSPACE),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    val key = when (label) {
                        BACKSPACE -> KeypadKey.Backspace
                        separator -> KeypadKey.Decimal
                        else -> KeypadKey.Digit(label.toInt())
                    }
                    KeypadButton(
                        label = label,
                        enabled = key != KeypadKey.Decimal || decimalEnabled,
                        onClick = { onKey(key) },
                        onLongClick = if (key == KeypadKey.Backspace) ({ onKey(KeypadKey.Clear) }) else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(56.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.combinedClickable(
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
        ) {
            if (label == BACKSPACE) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.keypad_backspace))
            } else {
                Text(label, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

private const val BACKSPACE = "⌫"

