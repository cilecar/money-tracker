package app.moneytracker.ui.recurring

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Frequency
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.expenses.edit.CategoryPickerSheet
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringEditScreen(onClose: () -> Unit, viewModel: RecurringEditViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val f = s.form
    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var startOpen by rememberSaveable { mutableStateOf(false) }
    var endOpen by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (f.original == null) R.string.recurring_new else R.string.recurring_edit)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
                actions = {
                    if (f.original != null) {
                        IconButton(onClick = viewModel::delete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        if (!f.loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = f.amountText,
                    onValueChange = { v -> viewModel.update { it.copy(amountText = v) } },
                    label = { Text(stringResource(R.string.recurring_amount)) },
                    suffix = { Text(f.currency.name) },
                    singleLine = true,
                    isError = f.amountText.isNotBlank() && f.amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CurrencyCode.entries.forEachIndexed { i, c ->
                        SegmentedButton(
                            selected = f.currency == c,
                            onClick = { viewModel.update { it.copy(currency = c) } },
                            shape = SegmentedButtonDefaults.itemShape(i, CurrencyCode.entries.size),
                        ) { Text(c.name) }
                    }
                }

                // Категория
                val selected = s.categories.firstOrNull { it.id == f.categoryId }
                AssistChip(
                    onClick = { pickerOpen = true },
                    label = { Text(selected?.displayName() ?: stringResource(R.string.recurring_choose_category)) },
                    leadingIcon = {
                        if (selected != null) CategoryAvatar(selected, size = 20.dp) else Icon(Icons.Default.GridView, null, Modifier.size(18.dp))
                    },
                )

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.paymentMethods.forEach { m ->
                        FilterChip(
                            selected = m.id == f.paymentMethodId,
                            onClick = { viewModel.onPaymentMethod(m) },
                            label = { Text(m.displayName() + (m.currency?.let { " · ${it.name}" } ?: "")) },
                            leadingIcon = { Icon(CategoryIcons.forPaymentType(m.type), null, Modifier.size(18.dp)) },
                        )
                    }
                }

                // Расписание
                Text(stringResource(R.string.recurring_schedule), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        Frequency.DAILY to R.string.frequency_daily,
                        Frequency.WEEKLY to R.string.frequency_weekly,
                        Frequency.MONTHLY to R.string.frequency_monthly,
                        Frequency.YEARLY to R.string.frequency_yearly,
                    )
                    options.forEachIndexed { i, (fr, label) ->
                        SegmentedButton(
                            selected = f.frequency == fr,
                            onClick = { viewModel.update { it.copy(frequency = fr) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.recurring_every), modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.update { it.copy(interval = (it.interval - 1).coerceAtLeast(1)) } }, enabled = f.interval > 1) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text(f.interval.toString(), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.update { it.copy(interval = (it.interval + 1).coerceAtMost(99)) } }) {
                        Icon(Icons.Default.Add, null)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { startOpen = true },
                        label = { Text(stringResource(R.string.recurring_start, formatDate(f.startDate, locale))) },
                        leadingIcon = { Icon(Icons.Default.CalendarToday, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = { endOpen = true },
                        label = {
                            Text(f.endDate?.let { stringResource(R.string.recurring_end, formatDate(it, locale)) } ?: stringResource(R.string.recurring_no_end))
                        },
                        trailingIcon = if (f.endDate != null) {
                            { IconButton(onClick = { viewModel.update { it.copy(endDate = null) } }, Modifier.size(18.dp)) { Icon(Icons.Default.Close, null) } }
                        } else {
                            null
                        },
                    )
                }
                if (f.endBeforeStart) {
                    Text(stringResource(R.string.recurring_end_before_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (f.original == null && f.startDate.isBefore(s.today)) {
                    Text(
                        stringResource(R.string.recurring_past_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = f.comment,
                    onValueChange = { v -> viewModel.update { it.copy(comment = v) } },
                    label = { Text(stringResource(R.string.recurring_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = f.tagsText,
                    onValueChange = { v -> viewModel.update { it.copy(tagsText = v) } },
                    label = { Text(stringResource(R.string.expense_tags)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = viewModel::save, enabled = f.valid, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    if (pickerOpen) {
        CategoryPickerSheet(
            categories = s.categories.filter { !it.archived },
            selectedId = f.categoryId,
            onSelect = { c ->
                viewModel.update { it.copy(categoryId = c.id) }
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
    if (startOpen) DateDialog(f.startDate, onPick = { d -> viewModel.update { it.copy(startDate = d) } }, onDismiss = { startOpen = false })
    if (endOpen) DateDialog(f.endDate ?: f.startDate, onPick = { d -> viewModel.update { it.copy(endDate = d) } }, onDismiss = { endOpen = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}
