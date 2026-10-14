package app.moneytracker.ui.operations

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.expenses.edit.CategoryPickerSheet
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney

@Composable
fun RefundEditScreen(onClose: () -> Unit, viewModel: RefundEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    val s = state
    if (s == null || !s.form.loaded) {
        Scaffold { }
        return
    }
    val locale = currentLocale()
    OperationFormScaffold(
        title = stringResource(if (s.isNew) R.string.refund_new else R.string.refund_edit),
        onClose = onClose,
        canSave = s.canSave,
        onSave = viewModel::save,
        onKey = viewModel::onKey,
        decimalEnabled = s.form.currency.fractionDigits > 0,
    ) {
        AmountHeader(s.form.amount, s.form.currency, s.conversions, s.ratesDate, viewModel::onCurrency, color = IncomeColor, prefix = "+")
        val expense = s.form.expense
        if (s.categoryEditable) {
            AssistChip(
                onClick = { pickerOpen = true },
                label = { Text(s.selectedCategory?.displayName() ?: stringResource(R.string.refund_choose_category)) },
                leadingIcon = s.selectedCategory?.let { c -> { CategoryAvatar(c, size = 20.dp) } }
                    ?: { Icon(Icons.Default.GridView, contentDescription = null, Modifier.size(18.dp)) },
            )
        } else {
            ListItem(
                leadingContent = { CategoryAvatar(s.selectedCategory) },
                headlineContent = { Text(stringResource(R.string.refund_for, s.selectedCategory?.displayName() ?: "—")) },
                supportingContent = expense?.let { e ->
                    { Text(formatMoney(e.amount, locale) + " · " + formatDate(e.localDate, locale)) }
                },
            )
        }
        Text(
            stringResource(R.string.refund_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccountChips(s.accounts, s.form.accountId, viewModel::onAccount)
        DateTimeRow(s.form.dateTime, viewModel::onDate, viewModel::onTime)
        CommentField(s.form.comment, viewModel::onComment)
    }
    if (pickerOpen) {
        CategoryPickerSheet(
            categories = s.categories,
            selectedId = s.form.categoryId,
            onSelect = {
                viewModel.onCategory(it.id)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
