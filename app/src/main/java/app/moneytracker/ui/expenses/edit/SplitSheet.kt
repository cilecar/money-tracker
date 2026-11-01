package app.moneytracker.ui.expenses.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import app.moneytracker.R
import app.moneytracker.domain.model.ShareError
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatAmount
import app.moneytracker.ui.format.formatMoney

/**
 * Чип рядом с датой и временем: функция нужна не каждый день, поэтому не отдельной строкой.
 * «Разделить» или «Доля 1 000 RSD», если покупка уже разделена.
 */
@Composable
fun SplitChip(s: ExpenseEditUiState, onClick: () -> Unit) {
    val locale = currentLocale()
    val label = s.myShare?.takeIf { s.form.shares.isNotEmpty() }?.let { stringResource(R.string.split_chip_share, formatAmount(it, locale)) }
        ?: stringResource(R.string.split_action)
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, Modifier.size(18.dp)) },
    )
}

/** Ошибка в долях (сумму расхода уменьшили ниже долей) — мелкой строкой под датой. */
@Composable
fun SplitError(s: ExpenseEditUiState) {
    s.shareError?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Кто скидывается и по сколько; моя доля — остаток. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SplitSheet(s: ExpenseEditUiState, viewModel: ExpenseEditViewModel, onDismiss: () -> Unit) {
    val locale = currentLocale()
    var newPersonOpen by rememberSaveable { mutableStateOf(false) }
    val selected = s.form.shares.associateBy { it.personId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.split_title), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.persons.forEach { person ->
                    val draft = selected[person.id]
                    FilterChip(
                        selected = draft != null,
                        // Погашенную долю не убрать: деньги за неё уже пришли.
                        enabled = draft?.settled != true,
                        onClick = { viewModel.onSplitToggle(person.id) },
                        label = { Text(person.name) },
                    )
                }
                AssistChip(
                    onClick = { newPersonOpen = true },
                    label = { Text(stringResource(R.string.split_new_person)) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            val names = s.persons.associate { it.id to it.name }
            s.form.shares.forEach { draft ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names[draft.personId] ?: "—", Modifier.weight(1f))
                    OutlinedTextField(
                        value = draft.amountText,
                        onValueChange = { viewModel.onShareAmount(draft.personId, it) },
                        enabled = !draft.settled,
                        singleLine = true,
                        suffix = { Text(s.form.currency.name) },
                        supportingText = if (draft.settled) ({ Text(stringResource(R.string.split_settled)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(180.dp),
                    )
                }
            }
            if (s.form.shares.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::onSplitEqually) { Text(stringResource(R.string.split_equally)) }
                    Text(
                        s.shareError?.let { stringResource(it.messageRes()) }
                            ?: s.myShare?.let { stringResource(R.string.split_my_share, formatMoney(it, locale)) }.orEmpty(),
                        color = if (s.shareError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.split_done)) }
        }
    }
    if (newPersonOpen) {
        NewPersonDialog(
            onConfirm = {
                viewModel.addPerson(it)
                newPersonOpen = false
            },
            onDismiss = { newPersonOpen = false },
        )
    }
}

@Composable
fun NewPersonDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.isEmpty()) R.string.person_new else R.string.person_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.person_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

fun ShareError.messageRes(): Int = when (this) {
    ShareError.NOT_POSITIVE -> R.string.split_error_amount
    ShareError.WRONG_CURRENCY -> R.string.split_error_amount
    ShareError.DUPLICATE_PERSON -> R.string.split_error_amount
    ShareError.EXCEEDS_TOTAL -> R.string.split_error_exceeds
}
