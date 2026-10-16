package app.moneytracker.ui.operations

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.IncomeCategoryAvatar
import app.moneytracker.ui.common.displayName
import androidx.compose.material3.Text

@Composable
fun IncomeEditScreen(
    onClose: () -> Unit,
    onSwitchKind: ((NewOperationKind) -> Unit)? = null,
    viewModel: IncomeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }

    val s = state
    if (s == null || !s.form.loaded) {
        Scaffold { }
        return
    }
    OperationFormScaffold(
        title = stringResource(if (s.isNew) R.string.income_new else R.string.income_edit),
        onClose = onClose,
        canSave = s.canSave,
        onSave = viewModel::save,
        onKey = viewModel::onKey,
        decimalEnabled = s.form.currency.fractionDigits > 0,
    ) {
        if (s.isNew && onSwitchKind != null) OperationKindTabs(NewOperationKind.INCOME, onSwitchKind)
        AmountHeader(
            s.form.amount, s.form.currency, s.conversions, s.ratesDate, viewModel::onCurrency,
            color = IncomeColor, prefix = "+", currencySwitchedFrom = s.form.currencySwitchedFrom,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.categories.forEach { c ->
                FilterChip(
                    selected = c.id == s.form.categoryId,
                    onClick = { viewModel.onCategory(c.id) },
                    label = { Text(c.displayName()) },
                    leadingIcon = { IncomeCategoryAvatar(c, size = 20.dp) },
                )
            }
        }
        AccountChips(s.accounts, s.form.accountId, viewModel::onAccount)
        DateTimeRow(s.form.dateTime, viewModel::onDate, viewModel::onTime)
        CommentField(s.form.comment, viewModel::onComment)
    }
}
