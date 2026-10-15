package app.moneytracker.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.AccountsTotal
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.format.symbol
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import app.moneytracker.domain.usecase.AccountError
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.ConfirmDialog
import app.moneytracker.ui.common.DeleteWithMoveDialog
import app.moneytracker.ui.common.MoveTarget
import app.moneytracker.ui.common.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openNew) { Icon(Icons.Default.Add, stringResource(R.string.account_new)) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            s.total?.let { t -> item { TotalHeader(t) } }
            items(s.active, key = { it.account.id }) { b ->
                val m = b.account
                ListItem(
                    modifier = Modifier.clickable { viewModel.openEdit(m) },
                    leadingContent = { Icon(CategoryIcons.forPaymentType(m.type), null) },
                    headlineContent = { Text(m.displayName()) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(m.type.labelRes()),
                                stringResource(R.string.account_default).takeIf { m.id == s.defaultId },
                                stringResource(R.string.account_unconverted, b.unconverted).takeIf { !b.isExact },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatMoney(b.balance, locale, approx = !b.isExact),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (b.balance.minor < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            IconButton(onClick = { viewModel.openReconcile(b) }) {
                                Icon(Icons.Default.Balance, stringResource(R.string.account_reconcile))
                            }
                        }
                    },
                )
            }
            if (s.archived.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.archive_title, s.archived.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(s.archived, key = { "a-${it.account.id}" }) { b ->
                    val m = b.account
                    ListItem(
                        modifier = Modifier.clickable { viewModel.openEdit(m) },
                        leadingContent = { Icon(CategoryIcons.forPaymentType(m.type), null) },
                        headlineContent = { Text(m.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supportingContent = { Text(formatMoney(b.balance, locale, approx = !b.isExact)) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.setArchived(m, false) }) {
                                Icon(Icons.Default.Unarchive, stringResource(R.string.action_restore))
                            }
                        },
                    )
                }
            }
        }
    }

    s.draft?.let { EditDialog(it, viewModel) }
    s.reconcile?.let { ReconcileDialog(it, viewModel) }
    DeleteDialogs(s, viewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(d: AccountDraft, viewModel: AccountsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeDraft,
        title = { Text(stringResource(if (d.original == null) R.string.account_new else R.string.account_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.account_name)) },
                    singleLine = true,
                    isError = d.error != null,
                    supportingText = d.error?.let { { Text(stringResource(it.messageRes())) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AccountType.entries.forEachIndexed { i, type ->
                        SegmentedButton(
                            selected = d.type == type,
                            onClick = { viewModel.updateDraft { it.copy(type = type) } },
                            shape = SegmentedButtonDefaults.itemShape(i, AccountType.entries.size),
                        ) { Text(stringResource(type.labelRes()), maxLines = 1) }
                    }
                }
                Text(stringResource(R.string.account_currency), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyCode.entries.forEach { c ->
                        FilterChip(
                            selected = d.currency == c,
                            onClick = { viewModel.updateDraft { it.copy(currency = c) } },
                            label = { Text(c.name) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.account_currency_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = d.openingBalanceText,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(openingBalanceText = v) } },
                    label = { Text(stringResource(R.string.account_opening_balance)) },
                    suffix = { Text(d.currency.symbol) },
                    singleLine = true,
                    isError = d.badAmount,
                    supportingText = if (d.badAmount) ({ Text(stringResource(R.string.account_error_bad_amount)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (d.type == AccountType.CARD) {
                    OutlinedTextField(
                        value = d.cardSuffix,
                        onValueChange = { v -> viewModel.updateDraft { it.copy(cardSuffix = v.filter(Char::isDigit).take(4)) } },
                        label = { Text(stringResource(R.string.account_card_suffix)) },
                        supportingText = { Text(stringResource(R.string.account_card_suffix_hint)) },
                        prefix = { Text("••") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                var pickDate by remember { mutableStateOf(false) }
                TextButton(onClick = { pickDate = true }) {
                    Text(stringResource(R.string.account_opening_date, formatDate(d.openingDate, currentLocale())))
                }
                Text(
                    stringResource(R.string.account_opening_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pickDate) {
                    OpeningDatePicker(d.openingDate, onPick = { date -> viewModel.updateDraft { it.copy(openingDate = date) } }, onDismiss = { pickDate = false })
                }
                val original = d.original
                if (original != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.setArchived(original, !original.archived) }) {
                            Text(stringResource(if (original.archived) R.string.action_restore else R.string.action_archive))
                        }
                        TextButton(onClick = { viewModel.requestDelete(original) }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDraft) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteDialogs(s: AccountsUiState, viewModel: AccountsViewModel) {
    when (val d = s.deleteDialog) {
        null -> Unit
        is PaymentDeleteDialog.Confirm -> ConfirmDialog(
            title = stringResource(R.string.account_delete_title, d.method.displayName()),
            message = null,
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(d.method) },
            onDismiss = viewModel::dismissDelete,
        )
        is PaymentDeleteDialog.Move -> {
            val count = d.usage.expenses + d.usage.recurring
            DeleteWithMoveDialog(
                title = stringResource(R.string.account_delete_title, d.method.displayName()),
                message = pluralStringResource(R.plurals.delete_has_expenses, count, count),
                targets = d.targets.map { t ->
                    MoveTarget(t.id, t.displayName()) { Icon(CategoryIcons.forPaymentType(t.type), null) }
                },
                onMove = { viewModel.delete(d.method, it) },
                onArchive = if (d.method.archived) null else ({ viewModel.setArchived(d.method, true) }),
                onDismiss = viewModel::dismissDelete,
            )
        }
        is PaymentDeleteDialog.Blocked -> ConfirmDialog(
            title = stringResource(R.string.account_delete_title, d.method.displayName()),
            message = stringResource(d.error.messageRes()),
            confirm = stringResource(android.R.string.ok),
            onConfirm = viewModel::dismissDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

fun AccountType.labelRes(): Int = when (this) {
    AccountType.CASH -> R.string.payment_type_cash
    AccountType.CARD -> R.string.payment_type_card
    AccountType.TRANSFER -> R.string.payment_type_transfer
}

private fun AccountError.messageRes(): Int = when (this) {
    AccountError.EMPTY_NAME -> R.string.error_empty_name
    AccountError.DUPLICATE_NAME -> R.string.error_duplicate_name
    AccountError.LAST_ACTIVE -> R.string.account_error_last
    AccountError.NEEDS_TARGET, AccountError.INVALID_TARGET -> R.string.delete_choose_target
    AccountError.CURRENCY_LOCKED -> R.string.account_error_currency_locked
    AccountError.HAS_TRANSFERS -> R.string.account_error_has_transfers
}

@Composable
private fun TotalHeader(t: AccountsTotal) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.accounts_total), style = MaterialTheme.typography.labelLarge)
        Text(formatMoney(t.total, locale, approx = !t.exact), style = MaterialTheme.typography.headlineMedium)
        if (t.missingRates) {
            Text(
                stringResource(R.string.accounts_total_no_rates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ReconcileDialog(d: ReconcileDraft, viewModel: AccountsViewModel) {
    val locale = currentLocale()
    val comment = stringResource(R.string.account_adjustment_comment)
    val difference = d.difference
    AlertDialog(
        onDismissRequest = viewModel::closeReconcile,
        title = { Text(stringResource(R.string.account_reconcile_title, d.balance.account.displayName())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_reconcile_calculated, formatMoney(d.balance.balance, locale, approx = !d.balance.isExact)))
                if (!d.balance.isExact) {
                    Text(
                        stringResource(R.string.account_reconcile_inexact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = d.actualText,
                    onValueChange = viewModel::updateReconcile,
                    label = { Text(stringResource(R.string.account_reconcile_actual)) },
                    suffix = { Text(d.balance.account.currency.symbol) },
                    singleLine = true,
                    isError = d.actualText.isNotBlank() && d.actual == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (d.actualText.isNotBlank() && difference != null) {
                    Text(
                        if (difference.isZero) {
                            stringResource(R.string.account_reconcile_same)
                        } else {
                            stringResource(R.string.account_reconcile_difference, (if (difference.minor > 0) "+" else "") + formatMoney(difference, locale))
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    stringResource(R.string.account_reconcile_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmReconcile(comment) },
                enabled = d.actualText.isNotBlank() && difference != null && !difference.isZero,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeReconcile) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpeningDatePicker(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // DatePicker работает в UTC-миллисекундах полуночи выбранного дня.
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
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

