package app.moneytracker.ui.paymentmethods

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
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.PaymentMethodType
import app.moneytracker.domain.usecase.PaymentMethodError
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.ConfirmDialog
import app.moneytracker.ui.common.DeleteWithMoveDialog
import app.moneytracker.ui.common.MoveTarget
import app.moneytracker.ui.common.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodsScreen(onBack: () -> Unit, viewModel: PaymentMethodsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_methods_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openNew) { Icon(Icons.Default.Add, stringResource(R.string.payment_method_new)) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(s.active, key = { it.id }) { m ->
                ListItem(
                    modifier = Modifier.clickable { viewModel.openEdit(m) },
                    leadingContent = { Icon(CategoryIcons.forPaymentType(m.type), null) },
                    headlineContent = { Text(m.displayName()) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(m.type.labelRes()),
                                m.currency?.name,
                                stringResource(R.string.payment_method_default).takeIf { m.id == s.defaultId },
                            ).joinToString(" · "),
                        )
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
                items(s.archived, key = { "a-${it.id}" }) { m ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.openEdit(m) },
                        leadingContent = { Icon(CategoryIcons.forPaymentType(m.type), null) },
                        headlineContent = { Text(m.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
    DeleteDialogs(s, viewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(d: PaymentMethodDraft, viewModel: PaymentMethodsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeDraft,
        title = { Text(stringResource(if (d.original == null) R.string.payment_method_new else R.string.payment_method_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.payment_method_name)) },
                    singleLine = true,
                    isError = d.error != null,
                    supportingText = d.error?.let { { Text(stringResource(it.messageRes())) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PaymentMethodType.entries.forEachIndexed { i, type ->
                        SegmentedButton(
                            selected = d.type == type,
                            onClick = { viewModel.updateDraft { it.copy(type = type) } },
                            shape = SegmentedButtonDefaults.itemShape(i, PaymentMethodType.entries.size),
                        ) { Text(stringResource(type.labelRes()), maxLines = 1) }
                    }
                }
                Text(stringResource(R.string.payment_method_currency), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = d.currency == null,
                        onClick = { viewModel.updateDraft { it.copy(currency = null) } },
                        label = { Text(stringResource(R.string.payment_method_any_currency)) },
                    )
                    CurrencyCode.entries.forEach { c ->
                        FilterChip(
                            selected = d.currency == c,
                            onClick = { viewModel.updateDraft { it.copy(currency = c) } },
                            label = { Text(c.name) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.payment_method_currency_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun DeleteDialogs(s: PaymentMethodsUiState, viewModel: PaymentMethodsViewModel) {
    when (val d = s.deleteDialog) {
        null -> Unit
        is PaymentDeleteDialog.Confirm -> ConfirmDialog(
            title = stringResource(R.string.payment_method_delete_title, d.method.displayName()),
            message = null,
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(d.method) },
            onDismiss = viewModel::dismissDelete,
        )
        is PaymentDeleteDialog.Move -> {
            val count = d.usage.expenses + d.usage.recurring
            DeleteWithMoveDialog(
                title = stringResource(R.string.payment_method_delete_title, d.method.displayName()),
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
            title = stringResource(R.string.payment_method_delete_title, d.method.displayName()),
            message = stringResource(d.error.messageRes()),
            confirm = stringResource(android.R.string.ok),
            onConfirm = viewModel::dismissDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

fun PaymentMethodType.labelRes(): Int = when (this) {
    PaymentMethodType.CASH -> R.string.payment_type_cash
    PaymentMethodType.CARD -> R.string.payment_type_card
    PaymentMethodType.TRANSFER -> R.string.payment_type_transfer
}

private fun PaymentMethodError.messageRes(): Int = when (this) {
    PaymentMethodError.EMPTY_NAME -> R.string.error_empty_name
    PaymentMethodError.DUPLICATE_NAME -> R.string.error_duplicate_name
    PaymentMethodError.LAST_ACTIVE -> R.string.payment_method_error_last
    PaymentMethodError.NEEDS_TARGET, PaymentMethodError.INVALID_TARGET -> R.string.delete_choose_target
}

