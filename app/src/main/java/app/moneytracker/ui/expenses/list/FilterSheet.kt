package app.moneytracker.ui.expenses.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.moneytracker.R
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.Money
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Шторка редактирует локальную копию фильтра и применяет её по кнопке —
 * список не пересчитывается на каждый тап по чипу.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    state: ExpenseListUiState,
    onApply: (ExpenseFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(state.filter) }
    val currency = state.displayCurrency
    var minText by remember { mutableStateOf(draft.minAmount?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var maxText by remember { mutableStateOf(draft.maxAmount?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val minAmount = Money.parse(minText, currency)
    val maxAmount = Money.parse(maxText, currency)
    val minError = minText.isNotBlank() && minAmount == null
    val maxError = maxText.isNotBlank() && maxAmount == null
    var rangeOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val locale = currentLocale()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(stringResource(R.string.filter_period))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val thisMonth = YearMonth.from(today)
                val presets = listOf(
                    R.string.filter_this_month to (thisMonth.atDay(1) to thisMonth.atEndOfMonth()),
                    R.string.filter_last_month to (thisMonth.minusMonths(1).atDay(1) to thisMonth.minusMonths(1).atEndOfMonth()),
                    R.string.filter_this_year to (today.withDayOfYear(1) to today.withDayOfYear(today.lengthOfYear())),
                )
                presets.forEach { (label, range) ->
                    FilterChip(
                        selected = draft.from == range.first && draft.to == range.second,
                        onClick = {
                            draft = if (draft.from == range.first && draft.to == range.second) {
                                draft.copy(from = null, to = null)
                            } else {
                                draft.copy(from = range.first, to = range.second)
                            }
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
                val custom = draft.from != null && presets.none { it.second == draft.from to draft.to }
                FilterChip(
                    selected = custom,
                    onClick = { rangeOpen = true },
                    label = {
                        Text(
                            if (custom) {
                                "${draft.from?.let { formatDate(it, locale) } ?: "…"} – ${draft.to?.let { formatDate(it, locale) } ?: "…"}"
                            } else {
                                stringResource(R.string.filter_custom_period)
                            },
                        )
                    },
                )
            }

            Section(stringResource(R.string.filter_categories))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { c ->
                    FilterChip(
                        selected = c.id in draft.categoryIds,
                        onClick = { draft = draft.copy(categoryIds = draft.categoryIds.toggle(c.id)) },
                        label = { Text(c.displayName()) },
                        leadingIcon = { CategoryAvatar(c, size = 20.dp) },
                    )
                }
            }

            Section(stringResource(R.string.filter_payment_methods))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.paymentMethods.forEach { m ->
                    FilterChip(
                        selected = m.id in draft.paymentMethodIds,
                        onClick = { draft = draft.copy(paymentMethodIds = draft.paymentMethodIds.toggle(m.id)) },
                        label = { Text(m.displayName()) },
                        leadingIcon = { Icon(CategoryIcons.forPaymentType(m.type), null, Modifier.size(18.dp)) },
                    )
                }
            }

            if (state.tags.isNotEmpty()) {
                Section(stringResource(R.string.filter_tags))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tags.forEach { t ->
                        FilterChip(
                            selected = draft.tags.any { it.equals(t, ignoreCase = true) },
                            onClick = {
                                val existing = draft.tags.firstOrNull { it.equals(t, ignoreCase = true) }
                                draft = draft.copy(tags = if (existing != null) draft.tags - existing else draft.tags + t)
                            },
                            label = { Text("#$t") },
                        )
                    }
                }
            }

            Section(stringResource(R.string.filter_amount, currency.name))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it },
                    isError = minError,
                    label = { Text(stringResource(R.string.filter_from)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { maxText = it },
                    isError = maxError,
                    label = { Text(stringResource(R.string.filter_to)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onApply(ExpenseFilter()) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.filter_reset)) }
                Button(
                    onClick = {
                        onApply(
                            draft.copy(
                                minAmount = minAmount,
                                maxAmount = maxAmount,
                            ),
                        )
                    },
                    enabled = !minError && !maxError,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }

    if (rangeOpen) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = draft.from?.toUtcMillis(),
            initialSelectedEndDateMillis = draft.to?.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { rangeOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val from = pickerState.selectedStartDateMillis?.toUtcDate()
                    draft = draft.copy(from = from, to = pickerState.selectedEndDateMillis?.toUtcDate() ?: from)
                    rangeOpen = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { rangeOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DateRangePicker(pickerState, modifier = Modifier.height(480.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

private fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

