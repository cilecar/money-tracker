package app.moneytracker.ui.receipts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.receipt.ItemNameParser
import app.moneytracker.domain.receipt.ItemUnit
import app.moneytracker.domain.receipt.ReceiptDetails
import app.moneytracker.domain.receipt.ReceiptItem
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDateTime
import app.moneytracker.ui.format.formatMoney

@Composable
fun ReceiptScreen(
    onBack: () -> Unit,
    onOpenExpense: (Long) -> Unit,
    onLink: (Long) -> Unit,
    onOpenProduct: (Long) -> Unit,
    viewModel: ReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.closed.collect { onBack() } }

    ReceiptScaffold(stringResource(R.string.receipt_title), onBack) { padding ->
        val d = state.details
        if (d == null) {
            if (!state.loading) ComingSoon(stringResource(R.string.receipts_empty), Modifier.padding(padding))
            return@ReceiptScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header(d) }
            val (discounts, items) = d.items.partition { it.isDiscount }
            if (items.isNotEmpty()) {
                item { Section(stringResource(R.string.receipt_items)) }
                items(items, key = { it.id }) { item ->
                    val article = item.articleId?.let(d.articles::get)
                    ItemRow(item, article?.name, article?.unit ?: ItemUnit.PCS, onClick = article?.let { a -> { onOpenProduct(a.productId) } })
                }
            }
            if (discounts.isNotEmpty()) {
                item { Section(stringResource(R.string.receipt_discounts)) }
                items(discounts, key = { it.id }) { ItemRow(it, null, ItemUnit.PCS, onClick = null) }
            }
            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.receipt_total), style = MaterialTheme.typography.titleMedium) },
                    trailingContent = { Text(formatMoney(rsd(d.receipt.totalMinor), currentLocale()), style = MaterialTheme.typography.titleMedium) },
                )
            }
            item { Actions(d, viewModel, onOpenExpense, onLink, onDelete = { confirmDelete = true }) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.receipt_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Header(d: ReceiptDetails) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(d.chain?.displayName ?: stringResource(R.string.receipts_loading_store), style = MaterialTheme.typography.titleLarge)
        d.store?.let { s ->
            Text(s.locationName, style = MaterialTheme.typography.bodyMedium)
            Text(listOf(s.address, s.city).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        }
        Text(formatDateTime(d.receipt.issuedAt, locale), style = MaterialTheme.typography.bodyMedium)
        when (d.receipt.status) {
            ReceiptStatus.PENDING -> Text(stringResource(R.string.receipt_pending), color = MaterialTheme.colorScheme.primary)
            ReceiptStatus.FAILED -> Text(stringResource(R.string.receipt_failed, d.receipt.error.orEmpty()), color = MaterialTheme.colorScheme.error)
            ReceiptStatus.LOADED -> Unit
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ItemRow(item: ReceiptItem, name: String?, unit: ItemUnit, onClick: (() -> Unit)?) {
    val locale = currentLocale()
    val names = unitNames()
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(name ?: ItemNameParser.stripTaxLabel(item.rawName)) },
        supportingContent = {
            Text(formatQuantity(item.quantityMilli, unit, names, locale) + " × " + formatMoney(rsd(item.unitPriceMinor), locale))
        },
        trailingContent = { Text(formatMoney(rsd(item.totalMinor), locale)) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(d: ReceiptDetails, viewModel: ReceiptViewModel, onOpenExpense: (Long) -> Unit, onLink: (Long) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.receipt.status == ReceiptStatus.FAILED) OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.receipt_retry)) }
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.receipt.url))) }) {
            Text(stringResource(R.string.receipt_open_site))
        }
        val expenseId = d.receipt.expenseId
        if (expenseId != null) {
            OutlinedButton(onClick = { onOpenExpense(expenseId) }) { Text(stringResource(R.string.receipt_open_expense)) }
            OutlinedButton(onClick = { viewModel.detach() }) { Text(stringResource(R.string.receipt_detach)) }
        } else {
            OutlinedButton(onClick = { onLink(d.receipt.id) }) { Text(stringResource(R.string.receipt_link)) }
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.receipt_delete), color = MaterialTheme.colorScheme.error) }
    }
}
