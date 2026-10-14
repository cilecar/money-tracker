package app.moneytracker.ui.operations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import java.math.BigDecimal
import java.math.MathContext
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.usecase.TransferError
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.format.formatRate
import app.moneytracker.ui.format.symbol

@Composable
fun TransferEditScreen(
    onClose: () -> Unit,
    onSwitchKind: ((NewOperationKind) -> Unit)? = null,
    viewModel: TransferEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }

    val s = state
    if (s == null || !s.form.loaded) {
        Scaffold { }
        return
    }
    val locale = currentLocale()
    val f = s.form
    OperationFormScaffold(
        title = stringResource(if (s.isNew) R.string.transfer_new else R.string.transfer_edit),
        onClose = onClose,
        canSave = s.canSave,
        onSave = viewModel::save,
        onKey = viewModel::onKey,
        decimalEnabled = true,
    ) {
        if (s.isNew && onSwitchKind != null) OperationKindTabs(NewOperationKind.TRANSFER, onSwitchKind)

        Text(stringResource(R.string.transfer_from), style = MaterialTheme.typography.titleSmall)
        AccountChips(s.accounts, f.fromAccountId, viewModel::onFrom)
        AmountField(f.fromAmount, s.from?.currency, "−", f.active == TransferField.FROM, Color.Unspecified) { viewModel.onField(TransferField.FROM) }

        Text(stringResource(R.string.transfer_to), style = MaterialTheme.typography.titleSmall)
        AccountChips(s.accounts, f.toAccountId, viewModel::onTo)
        AmountField(f.toAmount, s.to?.currency, "+", f.active == TransferField.TO, IncomeColor) { viewModel.onField(TransferField.TO) }

        if (s.isExchange) {
            // Курс показываем в направлении, где он ≥ 1: «1 € = 116.5 RSD», а не «1 RSD = 0.0086 €».
            val invert = (s.actualRate ?: s.apiRate)?.let { it < BigDecimal.ONE } == true
            val base = if (invert) s.to!!.currency else s.from!!.currency
            val quote = if (invert) s.from!!.currency else s.to!!.currency
            fun shown(rate: BigDecimal) = if (invert) BigDecimal.ONE.divide(rate, MathContext.DECIMAL64) else rate
            s.actualRate?.let {
                Text(stringResource(R.string.transfer_actual_rate, base.symbol, formatRate(shown(it), locale), quote.symbol), style = MaterialTheme.typography.bodyLarge)
            }
            val api = s.apiRate
            if (api != null) {
                // Отклонение — по направлению перевода: минус — получили меньше, чем по курсу API.
                val deviation = s.deviation
                Text(
                    stringResource(R.string.transfer_api_rate, formatRate(shown(api), locale)) +
                        (deviation?.let { " · " + (if (it.signum() > 0) "+" else "") + it.toPlainString() + "%" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((deviation?.signum() ?: 0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.expense_no_rates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.toAmount.isEmpty) {
                s.apiEstimate?.let {
                    Text(
                        stringResource(R.string.transfer_api_estimate, formatMoney(it, locale, approx = true)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(stringResource(R.string.transfer_fee), style = MaterialTheme.typography.titleSmall)
        AmountField(f.fee, s.from?.currency, "", f.active == TransferField.FEE, Color.Unspecified) { viewModel.onField(TransferField.FEE) }
        Text(stringResource(R.string.transfer_fee_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        DateTimeRow(f.dateTime, viewModel::onDate, viewModel::onTime)
        CommentField(f.comment, viewModel::onComment)
        f.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
    }
}

/** Сумма, которую набирает клавиатура, выделена рамкой; нажатие делает поле активным. */
@Composable
private fun AmountField(amount: AmountInput, currency: CurrencyCode?, sign: String, active: Boolean, color: Color, onClick: () -> Unit) {
    val locale = currentLocale()
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                (if (amount.isEmpty) "" else sign) + amount.display(locale) + " " + (currency?.symbol ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                color = when {
                    amount.isEmpty -> MaterialTheme.colorScheme.outline
                    color != Color.Unspecified -> color
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun TransferError.messageRes(): Int = when (this) {
    TransferError.SAME_ACCOUNT -> R.string.transfer_error_same
    TransferError.CURRENCY_MISMATCH -> R.string.transfer_error_currency
    TransferError.NOT_POSITIVE -> R.string.account_error_bad_amount
}
