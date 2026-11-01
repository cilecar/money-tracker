package app.moneytracker.ui.operations

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import app.moneytracker.ui.expenses.edit.NewPersonDialog

@Composable
fun PaidForMeEditScreen(
    onClose: () -> Unit,
    onSwitchKind: ((NewOperationKind) -> Unit)? = null,
    viewModel: PaidForMeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var newPersonOpen by rememberSaveable { mutableStateOf(false) }

    val s = state
    if (s == null || !s.form.loaded) {
        Scaffold { }
        return
    }
    OperationFormScaffold(
        title = stringResource(if (s.isNew) R.string.paid_for_me_new else R.string.paid_for_me_edit),
        onClose = onClose,
        canSave = s.canSave,
        onSave = viewModel::save,
        onKey = viewModel::onKey,
        decimalEnabled = s.form.currency.fractionDigits > 0,
    ) {
        if (s.isNew && onSwitchKind != null) OperationKindTabs(NewOperationKind.DEBT, onSwitchKind)
        AmountHeader(s.form.amount, s.form.currency, s.conversions, s.ratesDate, viewModel::onCurrency)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.selectedCategory?.let { c ->
                FilterChip(selected = true, onClick = { pickerOpen = true }, label = { Text(c.displayName()) }, leadingIcon = { CategoryAvatar(c, size = 20.dp) })
            }
            AssistChip(
                onClick = { pickerOpen = true },
                label = { Text(stringResource(R.string.expense_all_categories)) },
                leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        Text(stringResource(R.string.paid_for_me_who))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.persons.forEach { p ->
                FilterChip(selected = p.id == s.form.personId, onClick = { viewModel.onPerson(p.id) }, label = { Text(p.name) })
            }
            AssistChip(
                onClick = { newPersonOpen = true },
                label = { Text(stringResource(R.string.split_new_person)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        DateTimeRow(s.form.dateTime, viewModel::onDate, viewModel::onTime)
        CommentField(s.form.comment, viewModel::onComment)
    }
    if (pickerOpen) {
        CategoryPickerSheet(
            categories = s.categories.filter { !it.archived },
            selectedId = s.form.categoryId,
            onSelect = {
                viewModel.onCategory(it.id)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
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
