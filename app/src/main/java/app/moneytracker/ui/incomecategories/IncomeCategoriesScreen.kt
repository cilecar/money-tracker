package app.moneytracker.ui.incomecategories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.usecase.IncomeCategoryError
import app.moneytracker.ui.common.ConfirmDialog
import app.moneytracker.ui.common.DeleteWithMoveDialog
import app.moneytracker.ui.common.IncomeCategoryAvatar
import app.moneytracker.ui.common.MoveTarget
import app.moneytracker.ui.common.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeCategoriesScreen(onBack: () -> Unit, viewModel: IncomeCategoriesViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.income_categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openNew) { Icon(Icons.Default.Add, stringResource(R.string.income_category_new)) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(s.active, key = { it.id }) { c ->
                ListItem(
                    modifier = Modifier.clickable { viewModel.openEdit(c) },
                    leadingContent = { IncomeCategoryAvatar(c) },
                    headlineContent = { Text(c.displayName()) },
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
                items(s.archived, key = { "a-${it.id}" }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.openEdit(c) },
                        leadingContent = { IncomeCategoryAvatar(c) },
                        headlineContent = { Text(c.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.setArchived(c, false) }) {
                                Icon(Icons.Default.Unarchive, stringResource(R.string.action_restore))
                            }
                        },
                    )
                }
            }
        }
    }

    s.draft?.let { d ->
        AlertDialog(
            onDismissRequest = viewModel::closeDraft,
            title = { Text(stringResource(if (d.original == null) R.string.income_category_new else R.string.income_category_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = d.name,
                        onValueChange = viewModel::updateName,
                        label = { Text(stringResource(R.string.account_name)) },
                        singleLine = true,
                        isError = d.error != null,
                        supportingText = d.error?.let { { Text(stringResource(it.messageRes())) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
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

    when (val d = s.deleteDialog) {
        null -> Unit
        is IncomeCategoryDeleteDialog.Confirm -> ConfirmDialog(
            title = stringResource(R.string.income_category_delete_title, d.category.displayName()),
            message = null,
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(d.category) },
            onDismiss = viewModel::dismissDelete,
        )
        is IncomeCategoryDeleteDialog.Move -> DeleteWithMoveDialog(
            title = stringResource(R.string.income_category_delete_title, d.category.displayName()),
            message = stringResource(R.string.income_category_delete_move, d.count),
            targets = d.targets.map { t -> MoveTarget(t.id, t.displayName()) { IncomeCategoryAvatar(t, size = 24.dp) } },
            onMove = { viewModel.delete(d.category, it) },
            onArchive = if (d.category.archived) null else ({ viewModel.setArchived(d.category, true) }),
            onDismiss = viewModel::dismissDelete,
        )
        is IncomeCategoryDeleteDialog.Blocked -> ConfirmDialog(
            title = stringResource(R.string.income_category_delete_title, d.category.displayName()),
            message = stringResource(d.error.messageRes()),
            confirm = stringResource(android.R.string.ok),
            onConfirm = viewModel::dismissDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

private fun IncomeCategoryError.messageRes(): Int = when (this) {
    IncomeCategoryError.EMPTY_NAME -> R.string.error_empty_name
    IncomeCategoryError.DUPLICATE_NAME -> R.string.error_duplicate_name
    IncomeCategoryError.LAST_ACTIVE -> R.string.income_category_error_last
    IncomeCategoryError.NEEDS_TARGET, IncomeCategoryError.INVALID_TARGET -> R.string.delete_choose_target
}
