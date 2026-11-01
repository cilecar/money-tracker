package app.moneytracker.ui.debts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.categoryTitle
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.expenses.edit.NewPersonDialog
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.operations.AccountChips
import app.moneytracker.ui.operations.DateTimeRow
import app.moneytracker.ui.operations.IncomeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDebtsScreen(onBack: () -> Unit, viewModel: PersonDebtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var settledOpen by rememberSaveable { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Debt?>(null) }
    val person = state.person
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(person?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) } },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.person_rename)) }, onClick = {
                            menuOpen = false
                            renameOpen = true
                        })
                        val archived = person?.archived == true
                        DropdownMenuItem(
                            text = { Text(stringResource(if (archived) R.string.person_unarchive else R.string.person_archive)) },
                            onClick = {
                                menuOpen = false
                                viewModel.setArchived(!archived)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (state.open.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.person_no_open_debts), Modifier.padding(16.dp)) }
            }
            items(state.open, key = { "o-${it.debt.ref}" }) { row ->
                DebtListItem(row, trailingAction = {
                    TextButton(onClick = { settling = row.debt }) { Text(stringResource(R.string.debt_settle)) }
                })
            }
            if (state.settled.isNotEmpty()) {
                item(key = "settled-header") {
                    ListItem(
                        modifier = Modifier.clickable { settledOpen = !settledOpen },
                        headlineContent = { Text(stringResource(R.string.person_settled_debts, state.settled.size)) },
                        trailingContent = { Icon(if (settledOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
                    )
                }
                if (settledOpen) {
                    items(state.settled, key = { "s-${it.debt.ref}" }) { row ->
                        DebtListItem(row, trailingAction = {
                            TextButton(onClick = { viewModel.unsettle(row.debt.ref) }) { Text(stringResource(R.string.debt_unsettle)) }
                        })
                    }
                }
            }
        }
    }
    if (renameOpen && person != null) {
        NewPersonDialog(
            initial = person.name,
            onConfirm = {
                viewModel.rename(it)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }
    settling?.let { debt ->
        SettleDialog(debt, state, viewModel, onDismiss = { settling = null })
    }
}

/** «Ужин, 12 окт — должен тебе 1000 RSD» / «Кино — ты должен 400 RSD»; у погашенного — куда и когда. */
@Composable
private fun DebtListItem(row: DebtRow, trailingAction: @Composable () -> Unit) {
    val locale = currentLocale()
    val d = row.debt
    val owed = d.ref.direction == DebtDirection.OWED_TO_ME
    val direction = stringResource(if (owed) R.string.debts_owes_you else R.string.debts_you_owe)
    val settledLine = d.settlement?.let { s -> stringResource(R.string.debt_settled_on, formatDate(s.localDate, locale), formatMoney(s.amount, locale)) }
    ListItem(
        leadingContent = { CategoryAvatar(row.category) },
        headlineContent = { Text(listOfNotNull(d.comment, categoryTitle(row.category, row.parentCategory)).first()) },
        supportingContent = {
            Column {
                Text(listOf(formatDate(d.localDate, locale), "$direction ${formatMoney(d.amount, locale)}").joinToString(" · "),
                    color = if (owed) IncomeColor else MaterialTheme.colorScheme.onSurfaceVariant)
                settledLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = trailingAction,
    )
}

/** Счёт, сумма в его валюте, дата. */
@Composable
private fun SettleDialog(debt: Debt, state: PersonDebtsUiState, viewModel: PersonDebtsViewModel, onDismiss: () -> Unit) {
    val initialAccount = remember(debt) { state.defaultAccountFor(debt) }
    var account by remember(debt) { mutableStateOf<Account?>(initialAccount) }
    var amountText by remember(debt) { mutableStateOf(initialAccount?.let { state.defaultAmountText(debt, it) }.orEmpty()) }
    var dateTime by remember(debt) { mutableStateOf(viewModel.now()) }
    var invalid by remember(debt) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (debt.ref.direction == DebtDirection.OWED_TO_ME) R.string.debt_settle_in_title else R.string.debt_settle_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountChips(state.accounts, account?.id) { a ->
                    // Сменили счёт — подставляем сумму в его валюте.
                    if (a.currency != account?.currency) amountText = state.defaultAmountText(debt, a)
                    account = a
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.debt_settle_amount)) },
                    suffix = { Text(account?.currency?.name.orEmpty()) },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                DateTimeRow(dateTime, onDate = { dateTime = dateTime.with(it) }, onTime = { dateTime = dateTime.with(it) })
            }
        },
        confirmButton = {
            TextButton(enabled = account != null, onClick = {
                val a = account ?: return@TextButton
                if (viewModel.settle(debt.ref, a, amountText, dateTime)) onDismiss() else invalid = true
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
