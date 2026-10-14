package app.moneytracker.ui.rates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.ui.format.parsePositiveDecimal
import java.math.BigDecimal

/**
 * Ручной ввод курса «1 EUR = x». Принимает несколько валют сразу:
 * при первом запуске без сети нужны обе (RSD и RUB), с экрана курсов — одна.
 */
@Composable
fun ManualRatesDialog(
    currencies: List<CurrencyCode>,
    initial: Map<CurrencyCode, String>,
    onConfirm: (Map<CurrencyCode, BigDecimal>) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    val values = remember { mutableStateMapOf<CurrencyCode, String>().apply { putAll(initial) } }
    val parsed = currencies.associateWith { parsePositiveDecimal(values[it].orEmpty()) }
    val valid = parsed.values.all { it != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rates_manual_title)) },
        text = {
            Column {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                }
                currencies.forEachIndexed { index, currency ->
                    val text = values[currency].orEmpty()
                    OutlinedTextField(
                        value = text,
                        onValueChange = { values[currency] = it },
                        label = { Text(stringResource(R.string.rates_one_eur_in, currency.name)) },
                        suffix = { Text(currency.name) },
                        isError = text.isNotBlank() && parsed[currency] == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = if (index == currencies.lastIndex) ImeAction.Done else ImeAction.Next,
                        ),
                    )
                    if (index != currencies.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(parsed.mapValues { it.value!! }) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
