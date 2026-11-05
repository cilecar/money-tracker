package app.moneytracker.ui.prices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.moneytracker.domain.receipt.ChainWithStores
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.expenses.edit.CategoryPickerSheet
import app.moneytracker.ui.receipts.ReceiptScaffold

/** Сети и их точки: короткое имя и категория для новых расходов из чеков. */
@Composable
fun StoresScreen(onBack: () -> Unit, viewModel: StoresViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by rememberSaveable { mutableStateOf<Long?>(null) }
    ReceiptScaffold(stringResource(R.string.stores_title), onBack) { padding ->
        val s = state ?: return@ReceiptScaffold
        if (s.chains.isEmpty()) {
            ComingSoon(stringResource(R.string.stores_empty), Modifier.padding(padding))
            return@ReceiptScaffold
        }
        val categories = s.categories.associateBy { it.id }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(s.chains, key = { it.chain.id }) { c ->
                ChainCard(
                    c,
                    categoryName = c.chain.defaultCategoryId?.let(categories::get)?.displayName() ?: stringResource(R.string.stores_no_category),
                    onRename = { renaming = c.chain.id },
                    onPickCategory = { picking = c.chain.id },
                )
                HorizontalDivider()
            }
        }
        renaming?.let { id ->
            val chain = s.chains.firstOrNull { it.chain.id == id }?.chain ?: return@let
            ShortNameDialog(chain.shortName.orEmpty(), onDismiss = { renaming = null }, onSave = {
                viewModel.setShortName(id, it)
                renaming = null
            })
        }
        picking?.let { id ->
            CategoryPickerSheet(
                categories = s.categories.filter { !it.archived },
                selectedId = s.chains.firstOrNull { it.chain.id == id }?.chain?.defaultCategoryId,
                onSelect = {
                    viewModel.setCategory(id, it.id)
                    picking = null
                },
                onDismiss = { picking = null },
            )
        }
    }
}

@Composable
private fun ChainCard(c: ChainWithStores, categoryName: String, onRename: () -> Unit, onPickCategory: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        androidx.compose.foundation.layout.Row {
            Column(Modifier.weight(1f)) {
                Text(c.chain.displayName, style = MaterialTheme.typography.titleMedium)
                if (c.chain.shortName != null) Text(c.chain.officialName, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.stores_receipts, c.receiptCount), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, stringResource(R.string.stores_short_name)) }
        }
        AssistChip(onClick = onPickCategory, label = { Text(stringResource(R.string.stores_default_category) + ": " + categoryName) })
        c.stores.forEach { st ->
            Text(
                "• " + st.locationName + " — " + listOf(st.address, st.city).filter { it.isNotBlank() }.joinToString(", ") +
                    " (" + (c.receiptCountByStore[st.id] ?: 0) + ")",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ShortNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stores_short_name)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
