package app.moneytracker.ui.expenses.edit

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.format.symbol
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditScreen(onClose: () -> Unit, viewModel: ExpenseEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == ExpenseEditEvent.Close) onClose() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state?.isNew != false) R.string.expense_new else R.string.expense_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { padding ->
        val s = state
        if (s == null || !s.form.loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AmountHeader(s, onCurrency = viewModel::onCurrency)
                CategoryRow(s, onSelect = viewModel::onCategory)
                PaymentMethodRow(s, onSelect = viewModel::onPaymentMethod)
                DateTimeRow(s, onDate = viewModel::onDate, onTime = viewModel::onTime)
                OutlinedTextField(
                    value = s.form.comment,
                    onValueChange = viewModel::onComment,
                    label = { Text(stringResource(R.string.expense_comment)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                TagsField(s, viewModel)
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Пока открыта системная клавиатура (комментарий, теги), цифровая только мешает.
                if (!WindowInsets.isImeVisible) {
                    NumericKeypad(
                        onKey = viewModel::onKey,
                        decimalEnabled = s.form.currency.fractionDigits > 0,
                    )
                }
                Button(
                    onClick = viewModel::save,
                    enabled = s.canSave,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun AmountHeader(s: ExpenseEditUiState, onCurrency: (CurrencyCode) -> Unit) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CurrencyCode.entries.forEachIndexed { index, currency ->
                SegmentedButton(
                    selected = s.form.currency == currency,
                    onClick = { onCurrency(currency) },
                    shape = SegmentedButtonDefaults.itemShape(index, CurrencyCode.entries.size),
                ) { Text(currency.name) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = s.form.amount.display(locale) + " " + s.form.currency.symbol,
            fontSize = 44.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = if (s.form.amount.isEmpty) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        val sub = when {
            s.conversions.isNotEmpty() -> s.conversions.joinToString("   ") { formatMoney(it, locale, approx = true) }
            else -> stringResource(R.string.expense_no_rates)
        }
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.ratesDate?.let {
            Text(
                stringResource(R.string.rates_as_of, formatDate(it, locale)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CategoryRow(s: ExpenseEditUiState, onSelect: (Long) -> Unit) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.quickCategories.forEach { category ->
            FilterChip(
                selected = category.id == s.form.categoryId,
                onClick = { onSelect(category.id) },
                label = { Text(category.displayName()) },
                leadingIcon = { CategoryAvatar(category, size = 20.dp) },
            )
        }
        AssistChip(
            onClick = { pickerOpen = true },
            label = { Text(stringResource(R.string.expense_all_categories)) },
            leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
    if (pickerOpen) {
        CategoryPickerSheet(
            categories = s.categories,
            selectedId = s.form.categoryId,
            onSelect = {
                onSelect(it.id)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun PaymentMethodRow(s: ExpenseEditUiState, onSelect: (app.moneytracker.domain.model.PaymentMethod) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.paymentMethods.forEach { method ->
            FilterChip(
                selected = method.id == s.form.paymentMethodId,
                onClick = { onSelect(method) },
                label = {
                    Text(method.displayName() + (method.currency?.let { " · ${it.name}" } ?: ""))
                },
                leadingIcon = { Icon(CategoryIcons.forPaymentType(method.type), contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(s: ExpenseEditUiState, onDate: (java.time.LocalDate) -> Unit, onTime: (LocalTime) -> Unit) {
    val locale = currentLocale()
    val context = LocalContext.current
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    val dateTime = s.form.dateTime

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { dateOpen = true },
            label = { Text(formatDate(dateTime.toLocalDate(), locale)) },
            leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = { timeOpen = true },
            label = { Text(dateTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))) },
            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(18.dp)) },
        )
    }

    if (dateOpen) {
        // DatePicker работает в UTC-миллисекундах полуночи выбранного дня — конвертируем через UTC,
        // иначе в поясах восточнее Гринвича дата съезжает на день назад.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    dateOpen = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(pickerState) }
    }

    if (timeOpen) {
        val pickerState = rememberTimePickerState(
            initialHour = dateTime.hour,
            initialMinute = dateTime.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    onTime(LocalTime.of(pickerState.hour, pickerState.minute))
                    timeOpen = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(pickerState) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(s: ExpenseEditUiState, viewModel: ExpenseEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.form.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.form.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.removeTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove), Modifier.size(16.dp)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = s.form.tagDraft,
            onValueChange = viewModel::onTagDraft,
            label = { Text(stringResource(R.string.expense_tags)) },
            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.addTag() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.tagSuggestions.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.tagSuggestions.forEach { tag ->
                    SuggestionChip(onClick = { viewModel.addTag(tag) }, label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}
