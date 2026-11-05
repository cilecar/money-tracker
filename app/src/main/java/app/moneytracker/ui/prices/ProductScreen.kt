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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.receipt.ItemNameParser
import app.moneytracker.domain.receipt.PricePoint
import app.moneytracker.domain.receipt.Product
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.receipts.ReceiptScaffold
import app.moneytracker.ui.receipts.formatQuantity
import app.moneytracker.ui.receipts.perUnitLabel
import app.moneytracker.ui.receipts.rsd
import app.moneytracker.ui.receipts.unitNames

/** Карточка продукта: график цены по сетям, сводка по магазинам, история, связывание сетей. */
@Composable
fun ProductScreen(
    onBack: () -> Unit,
    onOpenExpense: (Long) -> Unit,
    onOpenReceipt: (Long) -> Unit,
    viewModel: ProductViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var linkOpen by rememberSaveable { mutableStateOf(false) }
    // Продукт исчез (его слили с другим) — закрываемся.
    LaunchedEffect(state) { if (state == ProductScreenState.Gone) onBack() }
    val s = (state as? ProductScreenState.Ready)?.ui ?: return

    val locale = currentLocale()
    val names = unitNames()
    val perUnit = perUnitLabel(s.product.unit, names)
    ReceiptScaffold(
        s.product.name,
        onBack,
        actions = { IconButton(onClick = { renameOpen = true }) { Icon(Icons.Default.Edit, stringResource(R.string.product_rename)) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { PriceChart(s.series, s.chainNames, Modifier.padding(16.dp)) }
            item { Section(stringResource(R.string.product_by_store)) }
            items(s.byChain, key = { "c" + it.chainId }) { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(s.chainNames[c.chainId].orEmpty(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.product_store_line,
                            formatMoney(rsd(c.lastMinor), locale) + " " + perUnit,
                            formatMoney(rsd(c.minMinor), locale),
                            formatMoney(rsd(c.maxMinor), locale),
                            formatDate(c.lastDate, locale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Точки сети показываем, только если их несколько.
                    if (c.stores.size > 1) {
                        c.stores.forEach { st ->
                            Text(
                                "• " + (s.stores[st.storeId]?.locationName.orEmpty()) + ": " + formatMoney(rsd(st.lastMinor), locale) + " · " + formatDate(st.lastDate, locale),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item { Section(stringResource(R.string.product_history)) }
            items(s.history, key = { "h" + it.itemId }) { p ->
                HistoryRow(p, s, perUnit, onClick = { p.expenseId?.let(onOpenExpense) ?: onOpenReceipt(p.receiptId) })
            }
            item { Section(stringResource(R.string.product_articles)) }
            items(s.articles, key = { "a" + it.article.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.article.name) },
                    supportingContent = { Text(a.chain.displayName + " · " + a.article.code) },
                    trailingContent = {
                        if (s.articles.size > 1) TextButton(onClick = { viewModel.unlink(a.article.id) }) { Text(stringResource(R.string.product_unlink)) }
                    },
                )
            }
            item {
                OutlinedButton(onClick = { linkOpen = true }, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Link, null)
                    Text(" " + stringResource(R.string.product_link))
                }
            }
        }
    }

    if (renameOpen) RenameDialog(s.product.name, onDismiss = { renameOpen = false }, onSave = { viewModel.rename(it); renameOpen = false })
    if (linkOpen) {
        LinkSheet(s, onDismiss = { linkOpen = false }, onPick = {
            viewModel.link(it.id)
            linkOpen = false
        })
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun HistoryRow(p: PricePoint, s: ProductUiState, perUnit: String, onClick: () -> Unit) {
    val locale = currentLocale()
    val names = unitNames()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(formatMoney(rsd(p.unitPriceMinor), locale) + " " + perUnit) },
        supportingContent = {
            Text(
                listOf(formatDate(p.date, locale), s.chainNames[p.chainId].orEmpty(), formatQuantity(p.quantityMilli, s.product.unit, names, locale))
                    .filter { it.isNotBlank() }.joinToString(" · "),
            )
        },
        trailingContent = { Text(formatMoney(rsd(p.totalMinor), locale)) },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.product_rename)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Выбор продукта из другой сети: сверху похожие, ниже поиск по всем продуктам той же единицы. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(s: ProductUiState, onDismiss: () -> Unit, onPick: (Product) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(query, s.linkable) {
        val q = ItemNameParser.normalize(query)
        if (q.isEmpty()) emptyList() else s.linkable.filter { ItemNameParser.normalize(it.name).contains(q) }.take(30)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text(stringResource(R.string.product_link_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.product_link_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (s.suggestions.isNotEmpty() && query.isBlank()) {
                item { Section(stringResource(R.string.product_link_suggestions)) }
                items(s.suggestions, key = { "s" + it.id }) { p -> PickRow(p, onPick) }
            }
            items(found, key = { "f" + it.id }) { p -> PickRow(p, onPick) }
        }
    }
}

@Composable
private fun PickRow(p: Product, onPick: (Product) -> Unit) {
    ListItem(modifier = Modifier.clickable { onPick(p) }, headlineContent = { Text(p.name) })
    HorizontalDivider()
}
