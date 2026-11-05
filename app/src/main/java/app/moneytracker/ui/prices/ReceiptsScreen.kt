package app.moneytracker.ui.prices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDateTime
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.receipts.ReceiptScaffold
import app.moneytracker.ui.receipts.rsd

/** Все чеки: ожидающие, с ошибкой, без расхода. */
@Composable
fun ReceiptsScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onLink: (Long) -> Unit,
    viewModel: ReceiptsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    ReceiptScaffold(stringResource(R.string.receipts_title), onBack) { padding ->
        val s = state ?: return@ReceiptScaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReceiptFilter.entries.forEach { f ->
                        FilterChip(selected = s.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(stringResource(f.label())) })
                    }
                }
            }
            if (s.rows.isEmpty()) item { ComingSoon(stringResource(R.string.receipts_empty)) }
            items(s.rows, key = { it.receipt.id }) { row ->
                val r = row.receipt
                ListItem(
                    modifier = Modifier.clickable { onOpen(r.id) },
                    headlineContent = { Text(row.chain?.displayName ?: stringResource(R.string.receipts_loading_store)) },
                    supportingContent = {
                        val status = when (r.status) {
                            ReceiptStatus.PENDING -> stringResource(R.string.receipts_filter_pending)
                            ReceiptStatus.FAILED -> stringResource(R.string.receipts_filter_failed)
                            ReceiptStatus.LOADED -> if (r.expenseId == null) stringResource(R.string.receipts_filter_unattached) else null
                        }
                        Text(listOfNotNull(formatDateTime(r.issuedAt, locale), row.store?.locationName, status).joinToString(" · "))
                    },
                    trailingContent = {
                        when {
                            r.status == ReceiptStatus.FAILED -> TextButton(onClick = { viewModel.retry(r.id) }) { Text(stringResource(R.string.receipt_retry)) }
                            r.status == ReceiptStatus.LOADED && r.expenseId == null ->
                                TextButton(onClick = { onLink(r.id) }) { Text(stringResource(R.string.receipt_attach)) }
                            else -> Text(formatMoney(rsd(r.totalMinor), locale), style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun ReceiptFilter.label(): Int = when (this) {
    ReceiptFilter.ALL -> R.string.receipts_filter_all
    ReceiptFilter.PENDING -> R.string.receipts_filter_pending
    ReceiptFilter.FAILED -> R.string.receipts_filter_failed
    ReceiptFilter.UNATTACHED -> R.string.receipts_filter_unattached
}
