package app.moneytracker.ui.prices

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.receipts.perUnitLabel
import app.moneytracker.ui.receipts.rememberQrScanner
import app.moneytracker.ui.receipts.rsd
import app.moneytracker.ui.receipts.unitNames

/** Вкладка «Цены»: товары из чеков с последней ценой; сканирование нового чека. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(
    onScanned: (String) -> Unit,
    onOpenProduct: (Long) -> Unit,
    onStores: () -> Unit,
    onReceipts: (filter: String) -> Unit,
    viewModel: PricesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scan = rememberQrScanner(
        onResult = onScanned,
        onError = { Toast.makeText(context, R.string.receipt_scan_failed, Toast.LENGTH_SHORT).show() },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prices_title)) },
                actions = {
                    IconButton(onClick = onStores) { Icon(Icons.Default.Store, stringResource(R.string.prices_stores)) }
                    IconButton(onClick = { onReceipts("ALL") }) { Icon(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.prices_receipts)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = scan,
                icon = { Icon(Icons.Default.QrCodeScanner, null) },
                text = { Text(stringResource(R.string.prices_scan)) },
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text(stringResource(R.string.prices_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (s.pendingCount > 0 || s.unattachedCount > 0) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.pendingCount > 0) {
                            AssistChip(onClick = { onReceipts("PENDING") }, label = { Text(stringResource(R.string.prices_banner_pending, s.pendingCount)) })
                        }
                        if (s.unattachedCount > 0) {
                            AssistChip(onClick = { onReceipts("UNATTACHED") }, label = { Text(stringResource(R.string.prices_banner_unattached, s.unattachedCount)) })
                        }
                    }
                }
            }
            if (s.items.isEmpty() && query.isBlank()) {
                item { ComingSoon(stringResource(R.string.prices_empty)) }
            }
            items(s.items, key = { it.productId }) { row ->
                PriceRowItem(row, onClick = { onOpenProduct(row.productId) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PriceRowItem(row: PriceRow, onClick: () -> Unit) {
    val locale = currentLocale()
    val names = unitNames()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(row.name) },
        supportingContent = {
            Text(
                listOf(
                    formatMoney(rsd(row.lastMinor), locale) + " " + perUnitLabel(row.unit, names),
                    row.chainName,
                    formatDate(row.date, locale),
                ).filter { it.isNotBlank() }.joinToString(" · "),
            )
        },
        trailingContent = { row.trendPercent?.takeIf { it != 0 }?.let { TrendLabel(it) } },
    )
}

/** Подорожание — красным, подешевение — зелёным. */
@Composable
fun TrendLabel(percent: Int) {
    val up = percent > 0
    Text(
        (if (up) "↑ +" else "↓ −") + kotlin.math.abs(percent) + "%",
        color = if (up) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
        style = MaterialTheme.typography.labelLarge,
    )
}
