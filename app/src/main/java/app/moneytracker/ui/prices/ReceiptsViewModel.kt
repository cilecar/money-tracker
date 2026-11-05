package app.moneytracker.ui.prices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.receipt.ReceiptRow
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.domain.receipt.ReceiptSyncScheduler
import app.moneytracker.domain.repository.ReceiptRepository
import app.moneytracker.ui.navigation.ReceiptsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReceiptFilter {
    ALL, PENDING, FAILED, UNATTACHED;

    companion object {
        fun of(name: String): ReceiptFilter = entries.firstOrNull { it.name == name } ?: ALL
    }
}

/** «Без расхода» — только загруженные: ожидающий чек ещё могут привязать при скане. */
fun filterRows(rows: List<ReceiptRow>, filter: ReceiptFilter): List<ReceiptRow> = rows
    .filter { r ->
        when (filter) {
            ReceiptFilter.ALL -> true
            ReceiptFilter.PENDING -> r.receipt.status == ReceiptStatus.PENDING
            ReceiptFilter.FAILED -> r.receipt.status == ReceiptStatus.FAILED
            ReceiptFilter.UNATTACHED -> r.receipt.status == ReceiptStatus.LOADED && r.receipt.expenseId == null
        }
    }
    .sortedByDescending { it.receipt.issuedAt }

data class ReceiptsUiState(val filter: ReceiptFilter, val rows: List<ReceiptRow>)

@HiltViewModel
class ReceiptsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val receipts: ReceiptRepository,
    private val sync: ReceiptSyncScheduler,
) : ViewModel() {
    private val filter = MutableStateFlow(ReceiptFilter.of(savedStateHandle.toRoute<ReceiptsRoute>().filter))

    val state: StateFlow<ReceiptsUiState?> = combine(receipts.observeRows(), filter.asStateFlow()) { rows, f ->
        ReceiptsUiState(f, filterRows(rows, f))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(f: ReceiptFilter) {
        filter.value = f
    }

    fun retry(receiptId: Long) = viewModelScope.launch {
        receipts.markPending(receiptId)
        sync.syncReceiptsWhenOnline()
    }
}
