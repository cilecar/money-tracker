package app.moneytracker.ui.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.receipt.Chain
import app.moneytracker.domain.receipt.ItemUnit
import app.moneytracker.domain.receipt.Prices
import app.moneytracker.domain.receipt.ProductOverview
import app.moneytracker.domain.receipt.ReceiptRow
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.domain.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class PriceRow(
    val productId: Long,
    val name: String,
    val lastMinor: Long,
    val unit: ItemUnit,
    val chainName: String,
    val date: LocalDate,
    val trendPercent: Int?,
)

data class PricesUiState(val items: List<PriceRow>, val pendingCount: Int, val unattachedCount: Int)

internal fun buildPricesState(overview: List<ProductOverview>, chains: Map<Long, Chain>, rows: List<ReceiptRow>, query: String): PricesUiState {
    val q = query.trim()
    return PricesUiState(
        items = overview
            .filter { q.isEmpty() || it.product.name.contains(q, ignoreCase = true) }
            .map {
                PriceRow(
                    productId = it.product.id,
                    name = it.product.name,
                    lastMinor = it.last.unitPriceMinor,
                    unit = it.product.unit,
                    chainName = chains[it.last.chainId]?.displayName.orEmpty(),
                    date = it.last.date,
                    trendPercent = it.trendPercent,
                )
            },
        pendingCount = rows.count { it.receipt.status == ReceiptStatus.PENDING },
        unattachedCount = rows.count { it.receipt.status == ReceiptStatus.LOADED && it.receipt.expenseId == null },
    )
}

@HiltViewModel
class PricesViewModel @Inject constructor(receipts: ReceiptRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<PricesUiState?> = combine(
        receipts.observeProducts(),
        receipts.observePricePoints(),
        receipts.observeChains(),
        receipts.observeRows(),
        _query,
    ) { products, points, chains, rows, q ->
        buildPricesState(Prices.overview(products, points), chains.associate { it.chain.id to it.chain }, rows, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQuery(text: String) {
        _query.value = text
    }
}
