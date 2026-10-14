package app.moneytracker.ui.operations

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.R
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.OperationKind
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.format.symbol
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Тип новой операции — переключатель вверху экрана добавления. */
enum class NewOperationKind(val labelRes: Int) {
    EXPENSE(R.string.operation_expense),
    INCOME(R.string.operation_income),
    TRANSFER(R.string.operation_transfer),
}

fun OperationKind.labelRes(): Int = when (this) {
    OperationKind.EXPENSE -> R.string.operation_expense
    OperationKind.INCOME -> R.string.operation_income
    OperationKind.REFUND -> R.string.operation_refund
    OperationKind.TRANSFER -> R.string.operation_transfer
}

@Composable
fun OperationKindTabs(selected: NewOperationKind, onSelect: (NewOperationKind) -> Unit) {
    val kinds = NewOperationKind.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { i, kind ->
            SegmentedButton(
                selected = kind == selected,
                onClick = { if (kind != selected) onSelect(kind) },
                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
            ) { Text(stringResource(kind.labelRes), maxLines = 1) }
        }
    }
}

/**
 * Валюта, крупная сумма, пересчёт в другие валюты и дата курса.
 * [color] — для доходов и возвратов сумма зелёная.
 */
@Composable
fun AmountHeader(
    amount: AmountInput,
    currency: CurrencyCode,
    conversions: List<Money>,
    ratesDate: LocalDate?,
    onCurrency: (CurrencyCode) -> Unit,
    color: Color = Color.Unspecified,
    prefix: String = "",
) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CurrencyCode.entries.forEachIndexed { index, c ->
                SegmentedButton(
                    selected = currency == c,
                    onClick = { onCurrency(c) },
                    shape = SegmentedButtonDefaults.itemShape(index, CurrencyCode.entries.size),
                ) { Text(c.name) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = prefix + amount.display(locale) + " " + currency.symbol,
            fontSize = 44.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = when {
                amount.isEmpty -> MaterialTheme.colorScheme.outline
                color != Color.Unspecified -> color
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        val sub = when {
            conversions.isNotEmpty() -> conversions.joinToString("   ") { formatMoney(it, locale, approx = true) }
            else -> stringResource(R.string.expense_no_rates)
        }
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ratesDate?.let {
            Text(
                stringResource(R.string.rates_as_of, formatDate(it, locale)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun AccountChips(accounts: List<Account>, selectedId: Long?, onSelect: (Account) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account ->
            FilterChip(
                selected = account.id == selectedId,
                onClick = { onSelect(account) },
                label = { Text(account.displayName() + " · " + account.currency.name) },
                leadingIcon = { Icon(CategoryIcons.forPaymentType(account.type), contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(dateTime: LocalDateTime, onDate: (LocalDate) -> Unit, onTime: (LocalTime) -> Unit) {
    val locale = currentLocale()
    val context = LocalContext.current
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }

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

/**
 * Каркас формы операции: прокручиваемые поля, под ними цифровая клавиатура и «Сохранить».
 * Клавиатуру прячем, пока открыта системная (комментарий).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OperationFormScaffold(
    title: String,
    onClose: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onKey: (app.moneytracker.ui.expenses.edit.KeypadKey) -> Unit,
    decimalEnabled: Boolean,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!WindowInsets.isImeVisible) {
                    app.moneytracker.ui.expenses.edit.NumericKeypad(onKey = onKey, decimalEnabled = decimalEnabled)
                }
                androidx.compose.material3.Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
fun CommentField(value: String, onValueChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.expense_comment)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Зелёный для поступлений: тот же в светлой и тёмной теме читается на фоне. */
val IncomeColor = Color(0xFF2E7D32)
