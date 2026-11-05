package app.moneytracker.ui.receipts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.receipt.ReceiptDetails
import app.moneytracker.domain.receipt.ReceiptSyncScheduler
import app.moneytracker.domain.repository.ReceiptRepository
import app.moneytracker.ui.navigation.ReceiptRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Загрузка ещё идёт ([loading]) или чека уже нет ([details] == null). */
data class ReceiptUiState(val loading: Boolean, val details: ReceiptDetails?)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val receipts: ReceiptRepository,
    private val sync: ReceiptSyncScheduler,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ReceiptRoute>().id

    val state: StateFlow<ReceiptUiState> = receipts.observeDetails(id)
        .map { ReceiptUiState(loading = false, details = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState(loading = true, details = null))

    private val _closed = Channel<Unit>(Channel.CONFLATED)
    val closed = _closed.receiveAsFlow()

    fun detach() = viewModelScope.launch { receipts.detach(id) }

    fun retry() = viewModelScope.launch {
        receipts.markPending(id)
        sync.syncReceiptsWhenOnline()
    }

    fun delete() = viewModelScope.launch {
        receipts.delete(id)
        _closed.send(Unit)
    }
}
